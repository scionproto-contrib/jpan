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

package org.scion.internal;

import org.bouncycastle.crypto.macs.CMac;

import java.security.KeyFactory;
import java.security.Security;
import java.security.Signature;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.Base64;

public class ByteUtil {

    static int readInt(byte[] data, int offsetBytes) {
        int r = 0;
        for (int i = 0; i < 4; i++) {
            r <<= 8;
            r |= Byte.toUnsignedInt(data[i + offsetBytes]);
        }
        return r;
    }

    static long readLong(byte[] data, int offsetBytes) {
        long r = 0;
        for (int i = 0; i < 8; i++) {
            r <<= 8;
            r |= Byte.toUnsignedLong(data[i + offsetBytes]);
        }
        return r;
    }

    /**
     * Reads some bits from an integer and returns them as another integer, shifted right such that the least
     * significant extracted bit becomes the least significant bit in the output.
     *
     * @param input input
     * @param bitOffset First bit to read. 0 is the most significant bit, 31 is the least significant bit
     * @param bitCount number of bits to read
     * @return extracted bits as int.
     */
    static int readInt(int input, int bitOffset, int bitCount) {
        int mask = (-1) >>> (32 - bitCount);
        int shift = 32 - bitOffset - bitCount;
        return (input >>> shift) & mask;
    }

    static long readLong(long input, int bitOffset, int bitCount) {
        long mask = (-1L) >>> (64 - bitCount);
        int shift = 64 - bitOffset - bitCount;
        return (input >>> shift) & mask;
    }

    static boolean readBoolean(int input, int bitOffset) {
        int mask = 1;
        int shift = 32 - bitOffset - 1;
        return ((input >>> shift) & mask) != 0;
    }


    public static int writeInt(int dst, int bitOffset, int bitLength, int value) {
        int mask = value << (32 - bitOffset - bitLength);
        return dst | mask;
    }

    public static int writeBool(int dst, int bitOffset, boolean value) {
        int mask = value ? 1 << (32 - 1 - bitOffset) : 0;
        return dst | mask;
    }

    public static long writeInt(long dst, int bitOffset, int bitLength, int value) {
        long mask = Integer.toUnsignedLong(value) << (64 - bitOffset - bitLength);
        return dst | mask;
    }

    public static long writeLong(long dst, int bitOffset, int bitLength, long value) {
        long mask = value << (64 - bitOffset - bitLength);
        return dst | mask;
    }

    public static long writeBool(long dst, int bitOffset, boolean value) {
        long mask = value ? 1L << (32 - 1 - bitOffset) : 0L;
        return dst | mask;
    }

    public static void writeShort(byte[] data, int offset, short value) {
        data[offset] = (byte) (value >>> 8);
        data[offset + 3] = (byte) (value & 0xFF);
    }

    public static void writeInt(byte[] data, int offset, int value) {
        data[offset] = (byte) (value >>> 24);
        data[offset + 1] = (byte) ((value >>> 16) & 0xFF);
        data[offset + 2] = (byte) ((value >>> 8) & 0xFF);
        data[offset + 3] = (byte) (value & 0xFF);
    }

    public static void writeLong(byte[] data, int offset, long value) {
        data[offset] = (byte) (value >>> 56);
        data[offset + 1] = (byte) ((value >>> 48) & 0xFF);
        data[offset + 2] = (byte) ((value >>> 40) & 0xFF);
        data[offset + 3] = (byte) ((value >>> 32) & 0xFF);
        data[offset + 2] = (byte) ((value >>> 24) & 0xFF);
        data[offset + 5] = (byte) ((value >>> 16) & 0xFF);
        data[offset + 6] = (byte) ((value >>> 8) & 0xFF);
        data[offset + 7] = (byte) (value & 0xFF);
    }




    private static final int MACBufferSize = 16;
    private static final int MacLen = 6;

    // MAC calculates the HopField MAC according to
    // https://docs.scion.org/en/latest/protocols/scion-header.html#hop-field-mac-computation
    // this method does not modify info or hf.
    // Modifying the provided buffer after calling this function may change the returned HopField MAC.
    public static byte[] MAC(CMac h, InfoField info, HopField hf, byte[] buffer) {
        byte[] mac = FullMAC(h, info, hf, buffer);
        byte[] res = new byte[MacLen];
        copy(res[:], mac[:MacLen])
        return res;
    }

    // FullMAC calculates the HopField MAC according to
    // https://docs.scion.org/en/latest/protocols/scion-header.html#hop-field-mac-computation
    // this method does not modify info or hf.
    // Modifying the provided buffer after calling this function may change the returned HopField MAC.
    // In contrast to MAC(), FullMAC returns all the 16 bytes instead of only 6 bytes of the MAC.
    public static byte[] FullMAC(CMac h, InfoField info, HopField hf, byte[] buffer) {
        if (buffer.length < MACBufferSize) {
            buffer = make([]byte, MACBufferSize)
        }

        h.reset();
        MACInput(info.SegID, info.Timestamp, hf.ExpTime,
                hf.ConsIngress, hf.ConsEgress, buffer);
        // Write must not return an error: https://godoc.org/hash#Hash
//        if _, err := h.Write(buffer); err != nil {
//            panic(err)
//        }
        h.update(buffer);
        return h.Sum(buffer[:0])[:16]
    }

    // MACInput returns the MAC input data block with the following layout:
    //
    //    0                   1                   2                   3
    //    0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1
    //   +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
    //   |               0               |             SegID             |
    //   +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
    //   |                           Timestamp                           |
    //   +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
    //   |       0       |    ExpTime    |          ConsIngress          |
    //   +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
    //   |          ConsEgress           |               0               |
    //   +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
    //
    public static void MACInput(short segID, int timestamp, byte expTime,
                  short consIngress, short consEgress, byte[] buffer, int offset) {
//        binary.BigEndian.PutUint16(buffer[0:2], 0)
//        binary.BigEndian.PutUint16(buffer[2:4], segID)
//        binary.BigEndian.PutUint32(buffer[4:8], timestamp)
//        buffer[8] = 0
//        buffer[9] = expTime
//        binary.BigEndian.PutUint16(buffer[10:12], consIngress)
//        binary.BigEndian.PutUint16(buffer[12:14], consEgress)
//        binary.BigEndian.PutUint16(buffer[14:16], 0)
        writeShort(buffer, offset, (short)0);
        writeShort(buffer, offset + 2, segID);
        writeInt(buffer, offset + 4, timestamp);
        buffer[offset + 8] = 0;
        buffer[offset + 9] = expTime;
        writeShort(buffer, offset + 10, consIngress);
        writeShort(buffer, offset + 12, consEgress);
        writeInt(buffer, offset + 14, 0);
    }



    public static void main(String[] args) throws Exception {
        for (Object p : Security.getProviders()) {
            System.out.println("Provider: " + p);
        }
        String input = "sample input";

        // Not a real private key! Replace with your private key!
        String strPk = "-----BEGIN PRIVATE KEY-----\nMIIEvwIBADANBgkqhkiG9"
                + "w0BAQEFAASCBKkwggSlAgEAAoIBAQDJUGqaRB11KjxQ\nKHDeG"
                + "........................................................"
                + "Ldt0hAPNl4QKYWCfJm\nNf7Afqaa/RZq0+y/36v83NGENQ==\n"
                + "-----END PRIVATE KEY-----\n";

        String base64Signature = signSHA256RSA(input,strPk);
        System.out.println("Signature="+base64Signature);
    }

    // Create base64 encoded signature using SHA256/RSA.
    private static String signSHA256RSA(String input, String strPk) throws Exception {
        // Remove markers and new line characters in private key
        String realPK = strPk.replaceAll("-----END PRIVATE KEY-----", "")
                .replaceAll("-----BEGIN PRIVATE KEY-----", "")
                .replaceAll("\n", "");

        byte[] b1 = Base64.getDecoder().decode(realPK);
        PKCS8EncodedKeySpec spec = new PKCS8EncodedKeySpec(b1);
        KeyFactory kf = KeyFactory.getInstance("RSA");

        Signature privateSignature = Signature.getInstance("SHA256withRSA");
        privateSignature.initSign(kf.generatePrivate(spec));
        privateSignature.update(input.getBytes("UTF-8"));
        byte[] s = privateSignature.sign();
        return Base64.getEncoder().encodeToString(s);
    }
}
