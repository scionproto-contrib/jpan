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

public interface Constants {

//    static <E extends ParseEnum<?>> E parse(Class<E> e, int code) {
//        for (int i = 0; i < HdrTypes.values().length; i++) {
//            ParseEnum<?> pe = e.getEnumConstants()[i];
//            if (pe.code() == code) {
//                return (E) pe;
//            }
//        }
//        throw new IllegalArgumentException("Unknown code: " + code);
//    }
//    static <E extends Enum<E>> E parse(E[] e, int code) {
//        for (int i = 0; i < HdrTypes.values().length; i++) {
//            ParseEnum pe = e[i];
//            if (pe.code() == code) {
//                return pe;
//            }
//        }
//        throw new IllegalArgumentException("Unknown code: " + code);
//    }


    interface ParseEnum<E extends Enum<E>> {

        int code();

//        default E parse2(int code) {
//            for (int i = 0; i < HdrTypes.values().length; i++) {
//                if (((Enum<E>)this).values()[i].code == code) {
//                    return HdrTypes.values()[i];
//                }
//            }
//            throw new IllegalArgumentException("Unknown code: " + code);
//        }
        static <E extends ParseEnum<?>> E parse(Class<E> e, int code) {
            for (int i = 0; i < HdrTypes.values().length; i++) {
                ParseEnum<?> pe = e.getEnumConstants()[i];
                if (pe.code() == code) {
                    return (E) pe;
                }
            }
            throw new IllegalArgumentException("Unknown code: " + code);
        }

    }

  enum PathTypes {
    Empty(0),
    SCION(1),
    OneHop(2),
    EPIC(3),
    COLIBRI(4);

    public final int code;

    PathTypes(int code) {
      this.code = code;
    }
  }

  // -- This is a combination of address type and length
  enum AddrTypes {
    IPv4(0x0), //   [0x0] = "IPv4", -- 0000
    SVC(0x4), // [0x4] = "SVC",  -- 0100
    IPv(0x3); // [0x3] = "IPv6", -- 0011

    public final int code;

    AddrTypes(int code) {
      this.code = code;
    }
  }

  enum HdrTypes implements Constants.ParseEnum<HdrTypes> {
    UDP(17),
    HOP_BY_HOP(200),
    END_TO_END(201),
    SCMP(202),
    BFD(203);

    public final int code;

    HdrTypes(int code) {
      this.code = code;
    }

      public static HdrTypes parse(int code) {
        return Constants.ParseEnum.parse(HdrTypes.class, code);
      }

      @Override
      public int code() {
          return code;
      }
  }

  enum SvcTypes {
    DS(0x0001),
    CS(0x0002),
    SB(0x0003),
    SIG(0x0004),
    HPS(0x0005);
    public final int code;

    SvcTypes(int code) {
      this.code = code;
    }
  }
}
