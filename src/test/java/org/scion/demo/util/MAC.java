// Copyright 2023 ETH Zurich
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//   http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package org.scion.demo.util;

import org.bouncycastle.crypto.BlockCipher;
import org.bouncycastle.crypto.CipherParameters;
import org.bouncycastle.crypto.engines.AESEngine;
import org.bouncycastle.crypto.macs.BlockCipherMac;
import org.bouncycastle.crypto.macs.CBCBlockCipherMac;
import org.bouncycastle.crypto.macs.CMac;
import org.bouncycastle.crypto.params.KeyParameter;
import org.bouncycastle.jcajce.PBKDF2KeyWithParameters;
import org.bouncycastle.jce.provider.BouncyCastleProvider;

import javax.crypto.Mac;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.PBEParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.security.Key;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.security.spec.InvalidKeySpecException;
import java.security.spec.KeySpec;

public class MAC {


    private static final String ErrCipherFailure = "Unable to initialize AES cipher";
    private static final String ErrMacFailure = "Unable to initialize Mac";

    private static final byte[] hfMacSalt = "Derive OF Key".getBytes();  // TODO correct way tp get bytes?
    private static final char[] hfMacSalt2 = "Derive OF Key".toCharArray();



    public static CMac InitMac(byte[] key) { //(hash.Hash, error) {
        BlockCipher aes = new AESEngine();
        CMac mac = new CMac(aes);


        //CBCBlockCipherMac blockCipherMac = new CBCBlockCipherMac();

//        block, err := aes.NewCipher(key)
//        if err != nil {
//            return nil, serrors.Wrap(ErrCipherFailure, err)
//        }
//        mac, err := cmac.New(block)
//        if err != nil {
//            return nil, serrors.Wrap(ErrMacFailure, err)
//        }
        return mac;
    }


    public static CMac HFMacFactory(byte[] key) {//} (func() hash.Hash, error) {

//        PBKDF2Parameters p = new PBKDF2Parameters("HmacSHA256", "UTF-8", hfMacSalt, 1000);
//        byte[] hfGenKey = new PBKDF2Engine(p).deriveKey(key);


        try {
        //hfGenKey := pbkdf2.Key(key, hfMacSalt, 1000, 16, sha256.New)
        SecretKeyFactory factorybc = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256");//, "BC");
        KeySpec keyspecbc = new PBEKeySpec("password".toCharArray(), hfMacSalt, 1000, 16);
        Key keybc = factorybc.generateSecret(keyspecbc);
        byte[] hfGenKey = keybc.getEncoded();

        CipherParameters params = new KeyParameter(key);
        CMac mac = InitMac(hfGenKey);
        mac.init(params); // TODO


//        Mac mac = Mac.getInstance("AESCMAC", new BouncyCastleProvider());
//        mac.init(new SecretKeySpec(key, "AES"));
//        mac.update(data);
//        mac.doFinal(moredata);

        //        f := func() hash.Hash {
        //            mac, _ := InitMac(hfGenKey)
        //            return mac
        //        }
        //        return f, nil
        return mac;
        } catch (NoSuchAlgorithmException | InvalidKeySpecException e) {
            throw new RuntimeException(e);
        }
    }


//    public static void HFMacFactory(byte[] key) (func() hash.Hash, error) {
//        // Generate keys
//        // This uses 16B keys with 1000 hash iterations, which is the same as the
//        // defaults used by pycrypto.
//        //hfGenKey := pbkdf2.Key(key, hfMacSalt, 1000, 16, sha256.New) // TODO sha256???
//
//        //        PBEKeySpec keySPec = new PBEKeySpec(key, hfMacSalt, 1000, 16);
//        //        // PBEParameterSpec spec = new PBEParameterSpec();
//        //        Mac mac = Mac.getInstance();
//        //        Key key = keySPec.
//        //        //mac.init(keySPec, spec);
//        //        mac.init(key);
//
//        Mac mac = Mac.getInstance("AESCMAC", new BouncyCastleProvider());
//        mac.init(new SecretKeySpec(key, "AES"));
//        mac.update(data);
//        mac.doFinal(moredata);
//
//
//
//
//
//
//        SecureRandom.getInstance("SHA1PRNG").nextBytes(salt);
//        PBKDF2Parameters p = new PBKDF2Parameters("HmacSHA256", "UTF-8", salt, 1000);
//        byte[] dk = new PBKDF2Engine(p).deriveKey("Hello World");
//        System.out.println(BinTools.bin2hex(dk));
//// Result is 64-character Base64 value. Note SHA256, different from RFC 6070.
//
//        // First check for MAC creation errors.
//        if _, err := InitMac(hfGenKey); err != nil {
//            return nil, err
//        }
//        f := func() hash.Hash {
//            mac, _ := InitMac(hfGenKey)
//            return mac
//        }
//        return f, nil
//    }


}
