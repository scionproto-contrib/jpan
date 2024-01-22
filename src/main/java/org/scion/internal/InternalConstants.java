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

public interface InternalConstants {

  interface ParseEnum {

    int code();

    static <E extends ParseEnum> E parse(Class<E> e, int code) {
      E[] values = e.getEnumConstants();
      for (int i = 0; i < values.length; i++) {
        E pe = values[i];
        if (pe.code() == code) {
          return pe;
        }
      }
      throw new IllegalArgumentException("Unknown code: " + code);
    }
  }

  enum PathTypes implements ParseEnum {
    Empty(0),
    SCION(1),
    OneHop(2),
    EPIC(3),
    COLIBRI(4);

    public final int code;

    PathTypes(int code) {
      this.code = code;
    }

    public static PathTypes parse(int code) {
      return ParseEnum.parse(PathTypes.class, code);
    }

    @Override
    public int code() {
      return code;
    }
  }

  // -- This is a combination of address type and length
  enum AddrTypes implements ParseEnum {
    IPv4(0x0), //   [0x0] = "IPv4", -- 0000
    SVC(0x4), // [0x4] = "SVC",  -- 0100
    IPv6(0x3); // [0x3] = "IPv6", -- 0011

    public final int code;

    AddrTypes(int code) {
      this.code = code;
    }

    public static AddrTypes parse(int code) {
      return ParseEnum.parse(AddrTypes.class, code);
    }

    @Override
    public int code() {
      return code;
    }
  }

  enum HdrTypes implements ParseEnum {
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
      return ParseEnum.parse(HdrTypes.class, code);
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
