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

package com.github.housepower.jdbc.data;

import com.github.housepower.jdbc.connect.NativeContext;
import com.github.housepower.jdbc.data.BlockSettings.Setting;
import com.github.housepower.jdbc.misc.Validate;
import com.github.housepower.jdbc.serde.BinaryDeserializer;
import com.github.housepower.jdbc.serde.BinarySerializer;

import java.io.IOException;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.Map;

public class Block {

    public static Block readFrom(BinaryDeserializer deserializer,
                                 NativeContext.ServerContext serverContext) throws IOException, SQLException {
        BlockSettings info = BlockSettings.readFrom(deserializer);

        int columnCnt = (int) deserializer.readVarInt();
        int rowCnt = (int) deserializer.readVarInt();

        IColumn[] columns = new IColumn[columnCnt];

        for (int i = 0; i < columnCnt; i++) {
            String name = deserializer.readUTF8StringBinary();
            String type = deserializer.readUTF8StringBinary();

            IDataType dataType = DataTypeFactory.get(type, serverContext);
            Object[] arr = dataType.deserializeBinaryBulk(rowCnt, deserializer);
            columns[i] = ColumnFactory.createColumn(name, dataType, arr);
        }

        return new Block(rowCnt, columns, info);
    }

    private final IColumn[] columns;
    private final BlockSettings settings;
    // position start with 1
    private final Map<String, Integer> nameAndPositions;
    private final Object[] rowData;
    private final int[] placeholderIndexes;
    private int rowCnt;

    public Block() {
        this(0, new IColumn[0]);
    }

    public Block(int rowCnt, IColumn[] columns) {
        this(rowCnt, columns, new BlockSettings(Setting.defaultValues()));
    }

    public Block(int rowCnt, IColumn[] columns, BlockSettings settings) {
        this.rowCnt = rowCnt;
        this.columns = columns;
        this.settings = settings;

        this.rowData = new Object[columns.length];
        this.nameAndPositions = new HashMap<>();
        this.placeholderIndexes = new int[columns.length];
        for (int i = 0; i < columns.length; i++) {
            nameAndPositions.put(columns[i].name(), i + 1);
            placeholderIndexes[i] = i;
        }
    }

    public int rowCnt() {
        return rowCnt;
    }

    public int columnCnt() {
        return columns.length;
    }

    public void appendRow() throws SQLException {
        int i = 0;
        try {
            for (; i < columns.length; i++) {
                columns[i].write(rowData[i]);
            }
            rowCnt++;
        } catch (IOException | ClassCastException e) {
            throw new SQLException("Exception processing value " + rowData[i] + " for column: " + columns[i].name(), e);
        }
    }

    public void setConstObject(int columnIdx, Object object) {
        rowData[columnIdx] = object;
    }

    public void setPlaceholderObject(int placeholderIdx, Object object) {
        rowData[placeholderIndexes[placeholderIdx]] = object;
    }

    public void incPlaceholderIndexes(int columnIdx) {
        for (int i = columnIdx; i < placeholderIndexes.length; i++) {
            placeholderIndexes[i] += 1;
        }
    }

    public void writeTo(BinarySerializer serializer) throws IOException, SQLException {
        settings.writeTo(serializer);

        serializer.writeVarInt(columns.length);
        serializer.writeVarInt(rowCnt);

        for (IColumn column : columns) {
            column.flushToSerializer(serializer, true);
        }
    }

    public IColumn getColumnByPosition(int position) throws SQLException {
        Validate.isTrue(position < columns.length,
                "Position " + position +
                        " is out of bound in Block.getByPosition, max position = " + (columns.length - 1));
        return columns[position];
    }

    public int getPositionByName(String columnName) throws SQLException {
        Validate.isTrue(nameAndPositions.containsKey(columnName), "Column '" + columnName + "' does not exist");
        return nameAndPositions.get(columnName);
    }

    public Object getObject(int columnIndex) throws SQLException {
        Validate.isTrue(columnIndex < columns.length,
                "Position " + columnIndex +
                        " is out of bound in Block.getByPosition, max position = " + (columns.length - 1));
        return rowData[columnIndex];
    }

    public void initWriteBuffer() {
        for (IColumn column : columns) {
            column.setColumnWriterBuffer(new ColumnWriterBuffer());
        }
    }
}
