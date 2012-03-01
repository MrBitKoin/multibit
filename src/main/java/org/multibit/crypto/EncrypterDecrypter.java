/**
 * Copyright 2012 multibit.org
 *
 * Licensed under the MIT license (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://opensource.org/licenses/mit-license.php
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.multibit.crypto;

import java.security.SecureRandom;

import org.apache.commons.codec.binary.Base64;
import org.bouncycastle.crypto.BufferedBlockCipher;
import org.bouncycastle.crypto.CipherParameters;
import org.bouncycastle.crypto.PBEParametersGenerator;
import org.bouncycastle.crypto.engines.AESFastEngine;
import org.bouncycastle.crypto.generators.OpenSSLPBEParametersGenerator;
import org.bouncycastle.crypto.modes.CBCBlockCipher;
import org.bouncycastle.crypto.paddings.PaddedBufferedBlockCipher;
import org.bouncycastle.crypto.params.ParametersWithIV;

import com.google.bitcoin.core.Utils;

/**
 * This class encrypts and decrypts a string in a manner that is compatible with OpenSSL
 * 
 * If you encrypt a string with this class you can decrypt it with the OpenSSL command:
 * openssl enc -d -aes-256-cbc -a -p -in cipher.txt -out plain.txt -pass pass:aTestPassword
 * 
 * where:
 *    cipher.txt = file containing the cipher text
 *    plain.txt - where you want the plaintext to be saved
 *    
 *  substitute your password for "aTestPassword" or remove the "-pass" parameter to be prompted
 *  
 * @author jim
 *
 */
public class EncrypterDecrypter {
    /**
     * The string encoding to use when converting strings to bytes
     */
    public static final String STRING_ENCODING = "UTF-8";

    /**
     * number of times the password & salt are hashed during key creation
     */
    private static final int NUMBER_OF_ITERATIONS = 1024;

    /**
     * key length
     */
    private static final int KEY_LENGTH = 256;

    /**
     * initialization vector length
     */
    private static final int IV_LENGTH = 128;

    /**
     * the length of the salt
     */
    private static final int SALT_LENGTH = 8;

    /**
     * OpenSSL salted prefix text
     */
    private static final String OPENSSL_SALTED_TEXT = "Salted__";
    
    private static SecureRandom secureRandom = new SecureRandom();

    // Get password to generate symmetric key with (or without IV)
    // To be used in an AES underlying cipher
    private CipherParameters getAESPasswordKey(char[] password, byte[] salt) throws Exception {
        //PBEParametersGenerator generator = new PKCS12ParametersGenerator(new SHA1Digest());
        //byte[] keyBytes = PBEParametersGenerator.PKCS12PasswordToBytes(password);
        
        PBEParametersGenerator generator = new OpenSSLPBEParametersGenerator();
        generator.init(PBEParametersGenerator.PKCS5PasswordToBytes(password), salt, NUMBER_OF_ITERATIONS);

        ParametersWithIV key = (ParametersWithIV) generator.generateDerivedParameters(KEY_LENGTH, IV_LENGTH);
        System.out.println("\nEncrypterDecrypter#getAESPasswordKey: salt was " + Utils.bytesToHexString(generator.getSalt()) );
        System.out.println("EncrypterDecrypter#getAESPasswordKey: iv was " + Utils.bytesToHexString(key.getIV()) );
               
        return key;
    }

    // Password based encryption using AES
    public String encrypt(String plainText, char[] password) throws EncrypterDecrypterException {
        try {
            // generate salt - each encryption call has a different salt
            byte[] salt = new byte[SALT_LENGTH];
            secureRandom.nextBytes(salt);

            final byte[] plainTextAsBytes = plainText.getBytes(STRING_ENCODING);

            ParametersWithIV key = (ParametersWithIV) getAESPasswordKey(password, salt);
                       
            // The following code uses an AES cipher to encrypt the message
            BufferedBlockCipher cipher = new PaddedBufferedBlockCipher(new CBCBlockCipher(new AESFastEngine()));
            cipher.init(true, key);
            byte[] encryptedBytes = new byte[cipher.getOutputSize(plainTextAsBytes.length)];
            int length = cipher.processBytes(plainTextAsBytes, 0, plainTextAsBytes.length, encryptedBytes, 0);

            cipher.doFinal(encryptedBytes, length);

            // OpenSSL adds the salt to the encrypted result as "Salted___" + salt in hex.
            // Do the same
            
            byte result[] = concat(salt, encryptedBytes);
            result = concat(OPENSSL_SALTED_TEXT.getBytes(STRING_ENCODING), result);

            return Base64.encodeBase64String(result);
        } catch (Exception e) {
            throw new EncrypterDecrypterException("Could not encrypt string '" + plainText + "'", e);
        }
    }

    // Password based decryption using AES
    public String decrypt(String textToDecode, char[] password) throws EncrypterDecrypterException {
        try {
            final byte[] decodeTextAsBytes = Base64.decodeBase64(textToDecode.getBytes(STRING_ENCODING));

            // extract the salt and bytes to decrypt
            byte[] saltPrefixBytes = OPENSSL_SALTED_TEXT.getBytes(STRING_ENCODING);
            int saltPrefixTextLength = saltPrefixBytes.length + SALT_LENGTH;
            
            byte[] prefixedSalt = new byte[saltPrefixTextLength];

            System.arraycopy(decodeTextAsBytes, 0, prefixedSalt, 0, saltPrefixTextLength);
             
            byte[] salt = new byte[SALT_LENGTH];

            System.arraycopy(prefixedSalt, saltPrefixBytes.length, salt, 0, SALT_LENGTH);
            System.out.println("EncrypterDecrypter#decrypt:salt = " + Utils.bytesToHexString(salt));
            
            byte[] cipherBytes = new byte[decodeTextAsBytes.length - saltPrefixTextLength];
            System.arraycopy(decodeTextAsBytes, saltPrefixTextLength, cipherBytes, 0, decodeTextAsBytes.length - saltPrefixTextLength);

            ParametersWithIV key = (ParametersWithIV) getAESPasswordKey(password, salt);

            // decrypt the message
            BufferedBlockCipher cipher = new PaddedBufferedBlockCipher(new CBCBlockCipher(new AESFastEngine()));
            cipher.init(false, key);

            byte[] decryptedBytes = new byte[cipher.getOutputSize(cipherBytes.length)];
            int length = cipher.processBytes(cipherBytes, 0, cipherBytes.length, decryptedBytes, 0);

            cipher.doFinal(decryptedBytes, length);
            
            // reconstruct the original string, trimming off any whitespace added by block padding
            String decryptedText = new String(decryptedBytes, STRING_ENCODING).trim();
            return decryptedText;
        } catch (Exception e) {
            throw new EncrypterDecrypterException("Could not decrypt string '" + textToDecode + "'", e);
        }
    }

    /**
     * Concatenate two byte arrays
     */
    private byte[] concat(byte[] arrayA, byte[] arrayB) {
        byte[] result = new byte[arrayA.length + arrayB.length];
        System.arraycopy(arrayA, 0, result, 0, arrayA.length);
        System.arraycopy(arrayB, 0, result, arrayA.length, arrayB.length);

        return result;
    }
}