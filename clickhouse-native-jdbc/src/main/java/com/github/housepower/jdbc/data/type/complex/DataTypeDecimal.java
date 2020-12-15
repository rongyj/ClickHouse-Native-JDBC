/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.github.housepower.jdbc.data.type.complex;

import com.github.housepower.jdbc.data.IDataType;
import com.github.housepower.jdbc.misc.BytesUtil;
import com.github.housepower.jdbc.misc.SQLLexer;
import com.github.housepower.jdbc.misc.Validate;
import com.github.housepower.jdbc.serde.BinaryDeserializer;
import com.github.housepower.jdbc.serde.BinarySerializer;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;
import java.sql.SQLException;
import java.sql.Types;
import java.util.Locale;

public class DataTypeDecimal implements IDataType {

    public static DataTypeCreator creator = (lexer, serverContext) -> {
        Validate.isTrue(lexer.character() == '(');
        Number precision = lexer.numberLiteral();
        Validate.isTrue(lexer.character() == ',');
        Number scale = lexer.numberLiteral();
        Validate.isTrue(lexer.character() == ')');
        return new DataTypeDecimal("Decimal(" + precision.intValue() + "," + scale.intValue() + ")",
                precision.intValue(), scale.intValue());
    };

    private final String name;
    private final int precision;
    private final int scale;
    private final BigDecimal scaleFactor;
    private final int nobits;

    //@see: https://clickhouse.tech/docs/en/sql-reference/data-types/decimal/
    public DataTypeDecimal(String name, int precision, int scale) {
        this.name = name;
        this.precision = precision;
        this.scale = scale;
        this.scaleFactor = BigDecimal.valueOf(Math.pow(10, scale));
        if (this.precision <= 9) {
            this.nobits = 32;
        } else if (this.precision <= 18) {
            this.nobits = 64;
        } else if (this.precision <= 38) {
            this.nobits = 128;
        } else if (this.precision <= 76) {
            this.nobits = 256;
        } else {
            throw new IllegalArgumentException(String.format(Locale.ENGLISH,
                    "Precision[%d] is out of boundary.", precision));
        }
    }

    @Override
    public String name() {
        return this.name;
    }

    @Override
    public int sqlTypeId() {
        return Types.DECIMAL;
    }

    @Override
    public Object defaultValue() {
        return new BigDecimal(0);
    }

    @Override
    public Class javaTypeClass() {
        return BigDecimal.class;
    }

    @Override
    public boolean nullable() {
        return false;
    }

    @Override
    public int getPrecision() {
        return precision;
    }

    @Override
    public int getScale() {
        return scale;
    }

    @Override
    public Object deserializeTextQuoted(SQLLexer lexer) throws SQLException {
        BigDecimal result;
        if (lexer.isCharacter('\'')) {
            String v = lexer.stringLiteral();
            result = new BigDecimal(v);
        } else {
            Number v = lexer.numberLiteral();
            result = BigDecimal.valueOf(v.doubleValue());
        }
        result = result.setScale(scale, RoundingMode.HALF_UP);
        return result;
    }

    @Override
    public void serializeBinary(Object data, BinarySerializer serializer) throws IOException {
        BigDecimal targetValue = ((BigDecimal) data).multiply(scaleFactor);
        switch (this.nobits) {
            case 32: {
                serializer.writeInt(targetValue.intValue());
                break;
            }
            case 64: {
                serializer.writeLong(targetValue.longValue());
                break;
            }
            case 128: {
                BigInteger res = targetValue.toBigInteger();

                serializer.writeLong(res.longValue());
                serializer.writeLong(res.shiftRight(64).longValue());
                break;
            }
            case 256: {
                BigInteger res = targetValue.toBigInteger();
                serializer.writeLong(targetValue.longValue());
                serializer.writeLong(res.shiftRight(64).longValue());
                serializer.writeLong(res.shiftRight(64 * 2).longValue());
                serializer.writeLong(res.shiftRight(64 * 3).longValue());
                break;
            }
            default: {
                throw new RuntimeException(String.format(Locale.ENGLISH,
                        "Unknown precision[%d] & scale[%d]", precision, scale));
            }
        }
    }

    @Override
    public Object deserializeBinary(BinaryDeserializer deserializer) throws SQLException, IOException {
        BigDecimal value;
        switch (this.nobits) {
            case 32: {
                int v = deserializer.readInt();
                value = BigDecimal.valueOf(v);
                value = value.divide(scaleFactor, scale, RoundingMode.HALF_UP);
                break;
            }
            case 64: {
                long v = deserializer.readLong();
                value = BigDecimal.valueOf(v);
                value = value.divide(scaleFactor, scale, RoundingMode.HALF_UP);
                break;
            }

            case 128: {
                long l1 = deserializer.readLong();
                long l2 = deserializer.readLong();

                BigInteger v1 = new BigInteger(1, BytesUtil.longToBytes(l1));
                BigInteger v2 = new BigInteger(1, BytesUtil.longToBytes(l2));

                value = new BigDecimal(v1.add(v2.shiftLeft(64)));
                value = value.divide(scaleFactor, scale, RoundingMode.HALF_UP);
                break;
            }

            case 256: {
                long l1 = deserializer.readLong();
                long l2 = deserializer.readLong();
                long l3 = deserializer.readLong();
                long l4 = deserializer.readLong();

                BigInteger v1 = new BigInteger(1, BytesUtil.longToBytes(l1));
                BigInteger v2 = new BigInteger(1, BytesUtil.longToBytes(l2));
                BigInteger v3 = new BigInteger(1, BytesUtil.longToBytes(l3));
                BigInteger v4 = new BigInteger(1, BytesUtil.longToBytes(l4));

                value = new BigDecimal(v1.add(v2.shiftLeft(64)).add(v3.shiftLeft(64 * 2)).add(v4.shiftLeft(64 * 3)));
                value = value.divide(scaleFactor, scale, RoundingMode.HALF_UP);
                break;
            }

            default: {
                throw new RuntimeException(String.format(Locale.ENGLISH,
                        "Unknown precision[%d] & scale[%d]", precision, scale));
            }
        }
        return value;
    }

    @Override
    public Object[] deserializeBinaryBulk(int rowCnt, BinaryDeserializer deserializer) throws SQLException, IOException {
        BigDecimal[] data = new BigDecimal[rowCnt];
        for (int i = 0; i < rowCnt; i++) {
            data[i] = (BigDecimal) this.deserializeBinary(deserializer);
        }
        return data;
    }
}
