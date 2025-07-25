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
package com.facebook.presto.mongodb;

import com.facebook.presto.common.type.Type;
import com.facebook.presto.spi.ColumnHandle;
import com.facebook.presto.spi.ColumnMetadata;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import org.bson.Document;

import java.util.Objects;

import static com.google.common.base.MoreObjects.toStringHelper;
import static java.util.Objects.requireNonNull;

public class MongoColumnHandle
        implements ColumnHandle
{
    public static final String SAMPLE_WEIGHT_COLUMN_NAME = "presto_sample_weight";

    private final String name;
    private final Type type;
    private final boolean hidden;

    @JsonCreator
    public MongoColumnHandle(
            @JsonProperty("name") String name,
            @JsonProperty("columnType") Type type,
            @JsonProperty("hidden") boolean hidden)
    {
        this.name = requireNonNull(name, "name is null");
        this.type = requireNonNull(type, "columnType is null");
        this.hidden = hidden;
    }

    @JsonProperty
    public String getName()
    {
        return name;
    }

    @JsonProperty("columnType")
    public Type getType()
    {
        return type;
    }

    @JsonProperty
    public boolean isHidden()
    {
        return hidden;
    }

    public ColumnMetadata toColumnMetadata()
    {
        return ColumnMetadata.builder()
                .setName(name)
                .setType(type)
                .setHidden(hidden)
                .build();
    }

    public ColumnMetadata toColumnMetadata(String name)
    {
        return ColumnMetadata.builder()
                .setName(name)
                .setType(type)
                .setHidden(hidden)
                .build();
    }

    public Document getDocument()
    {
        return new Document().append("name", name)
                .append("type", type.getTypeSignature().toString())
                .append("hidden", hidden);
    }

    @Override
    public int hashCode()
    {
        return Objects.hash(name, type, hidden);
    }

    @Override
    public boolean equals(Object obj)
    {
        if (this == obj) {
            return true;
        }
        if (obj == null || getClass() != obj.getClass()) {
            return false;
        }
        MongoColumnHandle other = (MongoColumnHandle) obj;
        return Objects.equals(name, other.name) &&
                Objects.equals(type, other.type) &&
                Objects.equals(hidden, other.hidden);
    }

    @Override
    public String toString()
    {
        return toStringHelper(this)
                .add("name", name)
                .add("type", type)
                .add("hidden", hidden)
                .toString();
    }
}
