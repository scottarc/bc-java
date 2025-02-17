package org.bouncycastle.openpgp.examples;

import org.bouncycastle.bcpg.ArmoredOutputStream;
import org.bouncycastle.bcpg.CompressionAlgorithmTags;
import org.bouncycastle.bcpg.SymmetricKeyAlgorithmTags;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.bouncycastle.openpgp.PGPCompressedData;
import org.bouncycastle.openpgp.PGPCompressedDataGenerator;
import org.bouncycastle.openpgp.PGPEncryptedDataGenerator;
import org.bouncycastle.openpgp.PGPEncryptedDataList;
import org.bouncycastle.openpgp.PGPException;
import org.bouncycastle.openpgp.PGPLiteralData;
import org.bouncycastle.openpgp.PGPPBEEncryptedData;
import org.bouncycastle.openpgp.PGPUtil;
import org.bouncycastle.openpgp.jcajce.JcaPGPObjectFactory;
import org.bouncycastle.openpgp.operator.PGPDataEncryptorBuilder;
import org.bouncycastle.openpgp.operator.jcajce.JcaPGPDigestCalculatorProviderBuilder;
import org.bouncycastle.openpgp.operator.jcajce.JcePBEDataDecryptorFactoryBuilder;
import org.bouncycastle.openpgp.operator.jcajce.JcePBEKeyEncryptionMethodGenerator;
import org.bouncycastle.openpgp.operator.jcajce.JcePGPDataEncryptorBuilder;
import org.bouncycastle.util.io.Streams;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.security.SecureRandom;
import java.security.Security;

/**
 * A simple utility class that encrypts/decrypts password based
 * encryption files.
 * <p>
 * To encrypt a file: PBEFileProcessor -e [-a] fileName passPhrase.<br>
 * If -a is specified the output file will be "ascii-armored".<br>
 * <p>
 * To decrypt: PBEFileProcessor -d fileName passPhrase.
 * <p>
 * Note: this example will silently overwrite files, nor does it pay any attention to
 * the specification of "_CONSOLE" in the filename. It also expects that a single pass phrase
 * will have been used.
 */
public class PBEFileProcessor
{
    private static void decryptFile(String inputFileName, char[] passPhrase)
            throws IOException, PGPException
    {
        InputStream in = new BufferedInputStream(new FileInputStream(inputFileName));
        decryptFile(in, passPhrase);
        in.close();
    }

    /*
     * decrypt the passed in message stream
     */
    private static void decryptFile(
            InputStream    in,
            char[]         passPhrase)
            throws IOException, PGPException
    {
        in = PGPUtil.getDecoderStream(in);

        JcaPGPObjectFactory pgpF = new JcaPGPObjectFactory(in);
        PGPEncryptedDataList enc;
        Object o = pgpF.nextObject();

        //
        // the first object might be a PGP marker packet.
        //
        if (o instanceof PGPEncryptedDataList)
        {
            enc = (PGPEncryptedDataList)o;
        }
        else
        {
            enc = (PGPEncryptedDataList)pgpF.nextObject();
        }

        PGPPBEEncryptedData pbe = (PGPPBEEncryptedData)enc.get(0);
        if (!pbe.isIntegrityProtected())
        {
            throw new PGPException("Message is not integrity protected!");
        }

        JcePBEDataDecryptorFactoryBuilder decryptorFactoryBuilder = new JcePBEDataDecryptorFactoryBuilder(
                new JcaPGPDigestCalculatorProviderBuilder().setProvider("BC").build())
                .setProvider("BC");

        InputStream clear = pbe.getDataStream(decryptorFactoryBuilder.build(passPhrase));
        pgpF = new JcaPGPObjectFactory(clear);

        //
        // if we're trying to read a file generated by someone other than us
        // the data might not be compressed, so we check the return type from
        // the factory and behave accordingly.
        //
        o = pgpF.nextObject();
        if (o instanceof PGPCompressedData)
        {
            PGPCompressedData   cData = (PGPCompressedData)o;
            pgpF = new JcaPGPObjectFactory(cData.getDataStream());
            o = pgpF.nextObject();
        }

        PGPLiteralData ld = (PGPLiteralData)o;
        InputStream unc = ld.getInputStream();

        OutputStream fOut = new FileOutputStream(ld.getFileName());

        Streams.pipeAll(unc, fOut, 8192);

        fOut.close();
        if (!pbe.verify())
        {
            System.err.println("message failed integrity check");
        }
        else
        {
            System.err.println("message integrity check passed");
        }
    }

    private static void encryptFile(
            String          outputFileName,
            String          inputFileName,
            char[]          passPhrase,
            boolean         armor)
            throws IOException
    {
        OutputStream out = new BufferedOutputStream(new FileOutputStream(outputFileName));
        encryptToStream(out, inputFileName, passPhrase, armor);
        out.close();
    }

    private static void encryptToStream(
            OutputStream    out,
            String          fileName,
            char[]          passPhrase,
            boolean         armor)
            throws IOException
    {
        if (armor)
        {
            out = new ArmoredOutputStream(out);
        }

        File file = new File(fileName);

        try
        {
            // Encryption
            PGPDataEncryptorBuilder dataEncBuilder = new JcePGPDataEncryptorBuilder(SymmetricKeyAlgorithmTags.AES_256)
                    .setProvider("BC")
                    .setWithIntegrityPacket(true)
                    .setSecureRandom(new SecureRandom());
            PGPEncryptedDataGenerator encGen = new PGPEncryptedDataGenerator(dataEncBuilder);
            encGen.addMethod(new JcePBEKeyEncryptionMethodGenerator(passPhrase).setProvider("BC"));
            OutputStream encOut = encGen.open(out, new byte[8192]);

            // Compression
            PGPCompressedDataGenerator compGen = new PGPCompressedDataGenerator(CompressionAlgorithmTags.ZIP);
            OutputStream compOut = compGen.open(encOut);

            // Literal Data
            PGPUtil.writeFileToLiteralData(compOut, PGPLiteralData.BINARY, file);

            compOut.flush();
            compOut.close();
            encOut.flush();
            encOut.close();

            if (armor)
            {
                out.flush();
                out.close();
            }
        }
        catch (PGPException e)
        {
            System.err.println(e);
            if (e.getUnderlyingException() != null)
            {
                e.getUnderlyingException().printStackTrace();
            }
        }
    }

    public static void main(
            String[] args)
            throws Exception
    {
        Security.addProvider(new BouncyCastleProvider());

        if (args[0].equals("-e"))
        {
            if (args[1].equals("-a"))
            {
                encryptFile(args[2] + ".asc", args[2], args[3].toCharArray(), true);
            }
            else
            {
                encryptFile(args[1] + ".bpg", args[1], args[2].toCharArray(), false);
            }
        }
        else if (args[0].equals("-d"))
        {
            decryptFile(args[1], args[2].toCharArray());
        }
        else
        {
            System.err.println("usage: PBEFileProcessor -e [-a]|-d file passPhrase");
        }
    }
}
