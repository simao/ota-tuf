package com.advancedtelematic.libtuf.crypt

import java.io.{StringReader, StringWriter}
import java.security
import java.security.interfaces.RSAPublicKey
import java.security.spec.{PKCS8EncodedKeySpec, X509EncodedKeySpec}
import java.security.{Signature => _, _}

import org.bouncycastle.jce.ECNamedCurveTable
import org.bouncycastle.jce.spec.ECParameterSpec
import com.advancedtelematic.libtuf.data.TufDataType.{ClientSignature, EdKeyType, EdTufKey, EdTufPrivateKey, KeyId, KeyType, RSATufKey, RSATufPrivateKey, RsaKeyType, Signature, SignatureMethod, SignedPayload, TufKey, TufPrivateKey, ValidKeyId, ValidSignature}
import org.bouncycastle.crypto.digests.SHA256Digest
import org.bouncycastle.openssl.jcajce.{JcaPEMKeyConverter, JcaPEMWriter}
import org.bouncycastle.util.encoders.{Base64, Hex}
import org.bouncycastle.openssl.{PEMKeyPair, PEMParser}
import org.bouncycastle.asn1.x509.SubjectPublicKeyInfo
import com.advancedtelematic.libats.data.RefinedUtils.RefineTry
import com.advancedtelematic.libtuf.data.TufDataType.SignatureMethod.SignatureMethod
import java.security.KeyFactory

import cats.data.Validated.Valid
import cats.data.{NonEmptyList, ValidatedNel}
import cats.implicits._
import io.circe.{Encoder, Json}
import io.circe.syntax._
import com.advancedtelematic.libtuf.crypt.CanonicalJson._
import com.advancedtelematic.libtuf.data.ClientDataType.{TufRole, RootRole}

import scala.util.control.NoStackTrace
import scala.util.Try

trait TufCrypto[T <: KeyType] {
  def parsePublic(keyVal: String): Try[T#Pub]

  def parsePrivate(keyVal: String): Try[T#Priv]

  def encode(keyVal: T#Pub): Json

  def encode(keyVal: T#Priv): Json

  def generateKeyPair(keySize: Int): (T#Pub, T#Priv)

  def convert(publicKey: PublicKey): T#Pub

  def signer: security.Signature

  val signatureMethod: SignatureMethod
}

object TufCrypto {
  import SignedPayloadSignatureOps._

  case class SignatureMethodMismatch(fromKey: SignatureMethod, fromSig: SignatureMethod)
      extends Exception(s"SignatureMethod mismatch, The key is for $fromKey but the signature is for $fromSig")
      with NoStackTrace

  val rsaCrypto = new RsaCrypto

  val edCrypto = new EdCrypto

  def signPayload[T : Encoder](key: TufPrivateKey, payload: T): Signature = {
    val bytes = payload.asJson.canonical.getBytes
    sign(key.keytype, key.keyval, bytes)
  }

  private def sign[T <: KeyType](keyType: T, privateKey: PrivateKey, data: Array[Byte]): Signature = {
    val signer = keyType.crypto.signer

    signer.initSign(privateKey)
    signer.update(data)
    val base64Sig = Base64.toBase64String(signer.sign())
    val validSignature = base64Sig.refineTry[ValidSignature].get

    Signature(validSignature, keyType.crypto.signatureMethod)
  }

  def convert[T <: KeyType](keyType: T, publicKey: PublicKey): T#Pub =
    keyType.crypto.convert(publicKey)

  def isValid(signature: Signature, publicKey: PublicKey, data: Array[Byte]): Boolean = {
    val signer = signature.method match {
      case SignatureMethod.RSASSA_PSS_SHA256 ⇒ rsaCrypto.signer
      case SignatureMethod.ED25519 ⇒ edCrypto.signer
      case other ⇒ throw new IllegalArgumentException(s"Unsupported signature method: $other")
    }
    val decodedSig = Base64.decode(signature.sig.value)
    signer.initVerify(publicKey)
    signer.update(data)
    signer.verify(decodedSig)
  }

  def isValid[T : Encoder](signature: ClientSignature, publicKey: PublicKey, value: T): Boolean = {
    val sig = Signature(signature.sig, signature.method)
    TufCrypto.isValid(sig, publicKey, value.asJson.canonical.getBytes)
  }

  implicit class PublicKeyOps(key: PublicKey) {
    val id: KeyId = {
      val publicKey = key.getEncoded
      val digest = new SHA256Digest()
      val buf = Array.fill[Byte](digest.getDigestSize)(0)
      digest.update(publicKey, 0, publicKey.length)
      digest.doFinal(buf, 0)
      Hex.toHexString(buf).refineTry[ValidKeyId].get
    }
  }

  implicit class KeyOps(key: Key) {
    def toPem = {
      val pemStrWriter = new StringWriter()
      val jcaPEMWriter = new JcaPEMWriter(pemStrWriter)
      jcaPEMWriter.writeObject(key)
      jcaPEMWriter.flush()
      pemStrWriter.toString
    }
  }

  def parsePublic[T <: KeyType](keyType: T, keyVal: String): Try[TufKey] =
    keyType.crypto.parsePublic(keyVal)

  def parsePrivate[T <: KeyType](keyType: T, keyVal: String): Try[TufPrivateKey] =
    keyType.crypto.parsePrivate(keyVal)

  def parsePublicPem(keyPem: String): Try[PublicKey] = Try {
    val parser = new PEMParser(new StringReader(keyPem))
    val converter = new JcaPEMKeyConverter()
    val pemKeyPair = parser.readObject().asInstanceOf[SubjectPublicKeyInfo]
    converter.getPublicKey(pemKeyPair)
  }

  def parsePrivatePem(keyPem: String): Try[PrivateKey] = Try {
    val parser = new PEMParser(new StringReader(keyPem))
    val converter = new JcaPEMKeyConverter()
    val pemKeyPair = parser.readObject().asInstanceOf[PEMKeyPair]
    converter.getPrivateKey(pemKeyPair.getPrivateKeyInfo)
  }

  def generateKeyPair[T <: KeyType](keyType: T, keySize: Int): (TufKey, TufPrivateKey) =
    keyType.crypto.generateKeyPair(keySize)

  def verifySignatures[T : Encoder](signedPayload: SignedPayload[T], publicKeys: Map[KeyId, TufKey]):
  ValidatedNel[String, List[ClientSignature]] = {
    import cats.implicits._

    signedPayload.signatures.par.map { (clientSig : ClientSignature) =>
      publicKeys.get(clientSig.keyid) match {
        case Some(key) =>
          if (TufCrypto.isValid(clientSig, key.keyval, signedPayload.signed))
            Valid(clientSig)
          else
            s"Invalid signature for key ${clientSig.keyid}".invalidNel
        case None =>
          s"payload signed with unknown key ${clientSig.keyid}".invalidNel
      }
    }.toList.sequenceU
  }

  def payloadSignatureIsValid[T : TufRole : Encoder](rootRole: RootRole, signedPayload: SignedPayload[T]): ValidatedNel[String, SignedPayload[T]] = {
    val signedPayloadRoleType = implicitly[TufRole[T]].roleType

    val publicKeys = rootRole.keys.filterKeys(keyId => rootRole.roles(signedPayloadRoleType).keyids.contains(keyId))

    val sigsByKeyId = signedPayload.signatures.map(s => s.keyid -> s).toMap

    val validSignatureCount: ValidatedNel[String, List[KeyId]] =
      sigsByKeyId.par.map { case (keyId, sig) =>
        publicKeys.get(keyId)
          .toRight(s"No public key available for key ${sig.keyid}")
          .ensure(s"Invalid signature for key ${sig.keyid}") { key =>
            signedPayload.isValidFor(key)
          }
          .map(_.id)
          .toValidatedNel
      }.toList.sequenceU

    val threshold = rootRole.roles(signedPayloadRoleType).threshold

    validSignatureCount.ensure(NonEmptyList.of(s"Valid signature count must be >= threshold ($threshold)")) { validSignatures =>
      validSignatures.size >= threshold && threshold > 0
    }.map(_ => signedPayload)
  }

}

protected [crypt] class EdCrypto extends TufCrypto[EdKeyType.type] {
  private lazy val generator = {
    val generator = KeyPairGenerator.getInstance("ECDSA", "BC")
    val ecP = ECNamedCurveTable.getParameterSpec("curve25519")
    val ecSpec = new ECParameterSpec(ecP.getCurve, ecP.getG, ecP.getN, ecP.getH, ecP.getSeed)
    generator.initialize(ecSpec)
    generator
  }

  override def parsePublic(publicKeyHex: String): Try[EdTufKey] = Try {
    val spec = new X509EncodedKeySpec(Hex.decode(publicKeyHex))
    val fac = KeyFactory.getInstance("ECDSA", "BC")
    EdTufKey(fac.generatePublic(spec))
  }

  override def parsePrivate(privateKeyHex: String): Try[EdTufPrivateKey] = Try {
    val spec = new PKCS8EncodedKeySpec(Hex.decode(privateKeyHex))
    val fac = KeyFactory.getInstance("ECDSA", "BC")
    EdTufPrivateKey(fac.generatePrivate(spec))
  }

  override def generateKeyPair(keySize: Int): (EdTufKey, EdTufPrivateKey) = {
    val keyPair = generator.generateKeyPair()
    (EdTufKey(keyPair.getPublic), EdTufPrivateKey(keyPair.getPrivate))
  }

  override def encode(keyVal: EdTufKey): Json = Json.fromString(Hex.toHexString(keyVal.keyval.getEncoded))

  override def encode(keyVal: EdTufPrivateKey): Json = Json.fromString(Hex.toHexString(keyVal.keyval.getEncoded))

  override def convert(publicKey: PublicKey): EdTufKey = EdTufKey(publicKey)

  override def signer: security.Signature = java.security.Signature.getInstance("SHA512withECDSA", "BC")

  override val signatureMethod: SignatureMethod = SignatureMethod.ED25519
}

protected [crypt] class RsaCrypto extends TufCrypto[RsaKeyType.type] {
  import TufCrypto.KeyOps

  override def generateKeyPair(size: Int = 2048): (RSATufKey, RSATufPrivateKey) = {
    if(size < 2048) {
      throw new IllegalArgumentException("Key size too small, must be >= 2048")
    }
    val keyGen = KeyPairGenerator.getInstance("RSA", "BC")
    keyGen.initialize(size)
    val keyPair = keyGen.generateKeyPair()
    (RSATufKey(keyPair.getPublic), RSATufPrivateKey(keyPair.getPrivate))
  }

  override def parsePublic(publicKey: String): Try[RSATufKey] = {
    val parser = new PEMParser(new StringReader(publicKey))
    val converter = new JcaPEMKeyConverter()

    Try {
      val pemKeyPair = parser.readObject().asInstanceOf[SubjectPublicKeyInfo]
      val pubKey = converter.getPublicKey(pemKeyPair)
      pubKey match {
        case rsaPubKey : RSAPublicKey if rsaPubKey.getModulus().bitLength() >= 2048 => RSATufKey(pubKey)
        case _ : RSAPublicKey => throw new IllegalArgumentException("Key size too small, must be >= 2048")
        case _ => throw new IllegalArgumentException("Key is not an RSAPublicKey")
      }
    }
  }

  override def parsePrivate(privateKey: String): Try[RSATufPrivateKey] = {
    val parser = new PEMParser(new StringReader(privateKey))
    val converter = new JcaPEMKeyConverter()

    Try {
      val pemKeyPair = parser.readObject().asInstanceOf[PEMKeyPair]
      RSATufPrivateKey(converter.getKeyPair(pemKeyPair).getPrivate)
    }
  }

  override def encode(keyVal: RSATufKey): Json = Json.fromString(keyVal.keyval.toPem)

  override def encode(keyVal: RSATufPrivateKey): Json = Json.fromString(keyVal.keyval.toPem)

  override def signer: security.Signature =
    java.security.Signature.getInstance("SHA256withRSAandMGF1", "BC") // RSASSA-PSS

  override val signatureMethod: SignatureMethod = SignatureMethod.RSASSA_PSS_SHA256

  override def convert(publicKey: PublicKey): RSATufKey = RSATufKey(publicKey)
}
