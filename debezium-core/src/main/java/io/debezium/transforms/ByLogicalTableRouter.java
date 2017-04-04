/*
 * Copyright Debezium Authors.
 *
 * Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */
package io.debezium.transforms;

import io.debezium.config.Configuration;
import io.debezium.config.Field;
import io.debezium.data.Envelope;
import io.debezium.util.AvroValidator;
import org.apache.kafka.common.cache.Cache;
import org.apache.kafka.common.cache.LRUCache;
import org.apache.kafka.common.cache.SynchronizedCache;
import org.apache.kafka.common.config.ConfigDef;
import org.apache.kafka.connect.connector.ConnectRecord;
import org.apache.kafka.connect.data.Schema;
import org.apache.kafka.connect.data.SchemaBuilder;
import org.apache.kafka.connect.data.Struct;
import org.apache.kafka.connect.errors.ConnectException;
import org.apache.kafka.connect.transforms.Transformation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.apache.kafka.connect.transforms.util.Requirements.requireStruct;

/**
 * A logical table consists of one or more physical tables with the same schema. A common use case is sharding -- the
 * two physical tables `db_shard1.my_table` and `db_shard2.my_table` together form one logical table.
 *
 * This Transformation allows us to change a record's topic name and send change events from multiple physical tables to
 * one topic. For instance, we might choose to send the two tables from the above example to the topic
 * `db_shard.my_table`. The config options {@link #TOPIC_REGEX} and {@link #TOPIC_REPLACEMENT} are used
 * to change the record's topic.
 *
 * Now that multiple physical tables can share a topic, the event's key may need to be augmented to include fields other
 * than just those for the record's primary/unique key, since these are not guaranteed to be unique across tables. We
 * need some identifier added to the key that distinguishes the different physical tables. The field name specified by
 * the config option {@link #KEY_FIELD_NAME} is added to the key schema for this purpose. By default, its value will
 * be the old topic name, but if a custom value is desired, the config options {@link #KEY_FIELD_REGEX} and
 * {@link #KEY_FIELD_REPLACEMENT} may be used to change it. For instance, in our above example, we might choose to
 * make the identifier `db_shard1` and `db_shard2` respectively.
 *
 * @author David Leibovic
 */
public class ByLogicalTableRouter<R extends ConnectRecord<R>> implements Transformation<R> {

    private final Logger logger = LoggerFactory.getLogger(getClass());
    private Pattern topicRegex;
    private String topicReplacement;
    private Pattern keyFieldRegex;
    private String keyFieldReplacement;
    private String keyFieldName;
    private Cache<Schema, Schema> keySchemaUpdateCache;
    private Cache<Schema, Schema> envelopeSchemaUpdateCache;
    private final AvroValidator schemaNameValidator = AvroValidator.create(logger);

    private static final Field TOPIC_REGEX = Field.create("topic.regex")
            .withDisplayName("Topic regex")
            .withType(ConfigDef.Type.STRING)
            .withWidth(ConfigDef.Width.LONG)
            .withImportance(ConfigDef.Importance.LOW)
            .withValidation(Field::isRequired, Field::isRegex)
            .withDescription("The regex used for extracting the name of the logical table from the original topic name.");

    private static final Field TOPIC_REPLACEMENT = Field.create("topic.replacement")
            .withDisplayName("Topic replacement")
            .withType(ConfigDef.Type.STRING)
            .withWidth(ConfigDef.Width.LONG)
            .withImportance(ConfigDef.Importance.LOW)
            .withValidation(Field::isRequired)
            .withDescription("The replacement string used in conjunction with " + TOPIC_REGEX.name() +
                    ". This will be used to create the new topic name.");

    private static final Field KEY_FIELD_REGEX = Field.create("key.field.regex")
            .withDisplayName("Key field regex")
            .withType(ConfigDef.Type.STRING)
            .withWidth(ConfigDef.Width.LONG)
            .withImportance(ConfigDef.Importance.LOW)
            .withValidation(Field::isRegex)
            .withDescription("The regex used for extracting the physical table identifier from the original topic " +
                "name. Now that multiple physical tables can share a topic, the event's key may need to be augmented " +
                "to include fields other than just those for the record's primary/unique key, since these are not " +
                "guaranteed to be unique across tables. We need some identifier added to the key that distinguishes " +
                "the different physical tables.");

    private static final Field KEY_FIELD_REPLACEMENT = Field.create("key.field.replacement")
            .withDisplayName("Key field replacement")
            .withType(ConfigDef.Type.STRING)
            .withWidth(ConfigDef.Width.LONG)
            .withImportance(ConfigDef.Importance.LOW)
            .withValidation(ByLogicalTableRouter::validateKeyFieldReplacement)
            .withDescription("The replacement string used in conjunction with " + KEY_FIELD_REGEX.name() +
                    ". This will be used to create the physical table identifier in the record's key.");

    private static final Field KEY_FIELD_NAME = Field.create("key.field.name")
            .withDisplayName("Key field name")
            .withType(ConfigDef.Type.STRING)
            .withWidth(ConfigDef.Width.LONG)
            .withImportance(ConfigDef.Importance.LOW)
            // Default is prefixed with "__dbz__" to minimize the likelihood of a conflict with an existing key field name.
            .withDefault("__dbz__physicalTableIdentifier")
            .withDescription("Each record's key schema will be augmented with this field name. The purpose of this " +
                    "field is to distinguish the different physical tables that can now share a single topic. Make " +
                    "sure not to configure a field name that is at risk of conflict with existing key schema field " +
                    "names.");

    @Override
    public void configure(Map<String, ?> props) {
        Configuration config = Configuration.from(props);
        final Field.Set configFields = Field.setOf(TOPIC_REGEX, TOPIC_REPLACEMENT, KEY_FIELD_REGEX,
                KEY_FIELD_REPLACEMENT);
        if (!config.validateAndRecord(configFields, logger::error)) {
            throw new ConnectException("Unable to validate config.");
        }

        topicRegex = Pattern.compile(config.getString(TOPIC_REGEX));
        topicReplacement = config.getString(TOPIC_REPLACEMENT);

        String keyFieldRegexString = config.getString(KEY_FIELD_REGEX);
        if (keyFieldRegexString != null) {
            keyFieldRegexString = keyFieldRegexString.trim();
        }
        if (keyFieldRegexString != null && keyFieldRegexString != "") {
            keyFieldRegex = Pattern.compile(config.getString(KEY_FIELD_REGEX));
            keyFieldReplacement = config.getString(KEY_FIELD_REPLACEMENT);
        }
        keyFieldName = config.getString(KEY_FIELD_NAME);

        keySchemaUpdateCache = new SynchronizedCache<>(new LRUCache<Schema, Schema>(16));
        envelopeSchemaUpdateCache = new SynchronizedCache<>(new LRUCache<Schema, Schema>(16));
    }

    @Override
    public R apply(R record) {
        final String oldTopic = record.topic();
        final String newTopic = determineNewTopic(oldTopic);
        if (newTopic == null) {
            return record;
        }

        logger.info("Applying topic name transformation from " + oldTopic + " to " + newTopic + ".");
        final Struct oldKey = requireStruct(record.key(), "Updating schema");
        final Schema newKeySchema = updateKeySchema(oldKey.schema(), newTopic);
        final Struct newKey = updateKey(newKeySchema, oldKey, oldTopic);

        if (record.value() == null) {
            // Value will be null in the case of a delete event tombstone
            return record.newRecord(newTopic, record.kafkaPartition(), newKeySchema, newKey, record.valueSchema(),
                    record.value(), record.timestamp());
        }

        final Struct oldEnvelope = requireStruct(record.value(), "Updating schema");
        final Schema newEnvelopeSchema = updateEnvelopeSchema(oldEnvelope.schema(), newTopic);
        final Struct newEnvelope = updateEnvelope(newEnvelopeSchema, oldEnvelope);
        return record.newRecord(newTopic, record.kafkaPartition(), newKeySchema, newKey, newEnvelopeSchema,
                newEnvelope, record.timestamp());
    }

    @Override
    public void close() {
        keySchemaUpdateCache = null;
        envelopeSchemaUpdateCache = null;
    }

    @Override
    public ConfigDef config() {
        ConfigDef config = new ConfigDef();
        Field.group(config, null, TOPIC_REGEX, TOPIC_REPLACEMENT, KEY_FIELD_REGEX,
                KEY_FIELD_REPLACEMENT);
        return config;
    }

    /**
     * @param oldTopic
     * @return return the new topic name, if the regex applies. Otherwise, return null.
     */
    private String determineNewTopic(String oldTopic) {
        final Matcher matcher = topicRegex.matcher(oldTopic);
        if (matcher.matches()) {
            return matcher.replaceFirst(topicReplacement);
        }
        return null;
    }

    private Schema updateKeySchema(Schema oldKeySchema, String newTopicName) {
        Schema newKeySchema = keySchemaUpdateCache.get(oldKeySchema);
        if (newKeySchema != null) {
            return newKeySchema;
        }

        final SchemaBuilder builder = copySchemaExcludingName(oldKeySchema, SchemaBuilder.struct());
        builder.name(schemaNameValidator.validate(newTopicName + ".Key"));

        // Now that multiple physical tables can share a topic, the event's key may need to be augmented to include
        // fields other than just those for the record's primary/unique key, since these are not guaranteed to be unique
        // across tables. We need some identifier added to the key that distinguishes the different physical tables.
        builder.field(keyFieldName, Schema.STRING_SCHEMA);

        newKeySchema = builder.build();
        keySchemaUpdateCache.put(oldKeySchema, newKeySchema);
        return newKeySchema;
    }

    private Struct updateKey(Schema newKeySchema, Struct oldKey, String oldTopic) {
        final Struct newKey = new Struct(newKeySchema);
        for (org.apache.kafka.connect.data.Field field : oldKey.schema().fields()) {
            newKey.put(field.name(), oldKey.get(field));
        }

        String physicalTableIdentifier = oldTopic;
        if (keyFieldRegex != null) {
            final Matcher matcher = keyFieldRegex.matcher(oldTopic);
            if (matcher.matches()) {
                physicalTableIdentifier = matcher.replaceFirst(keyFieldReplacement);
            }
        }

        newKey.put(keyFieldName, physicalTableIdentifier);
        return newKey;
    }

    private Schema updateEnvelopeSchema(Schema oldEnvelopeSchema, String newTopicName) {
        Schema newEnvelopeSchema = envelopeSchemaUpdateCache.get(oldEnvelopeSchema);
        if (newEnvelopeSchema != null) {
            return newEnvelopeSchema;
        }

        final Schema oldValueSchema = oldEnvelopeSchema.field(Envelope.FieldName.BEFORE).schema();
        final SchemaBuilder valueBuilder = copySchemaExcludingName(oldValueSchema, SchemaBuilder.struct());
        valueBuilder.name(schemaNameValidator.validate(newTopicName + ".Value"));
        final Schema newValueSchema = valueBuilder.build();

        final SchemaBuilder envelopeBuilder = copySchemaExcludingName(oldEnvelopeSchema, SchemaBuilder.struct(), false);
        for (org.apache.kafka.connect.data.Field field : oldEnvelopeSchema.fields()) {
            final String fieldName = field.name();
            Schema fieldSchema = field.schema();
            if (fieldName == Envelope.FieldName.BEFORE || fieldName == Envelope.FieldName.AFTER) {
                fieldSchema = newValueSchema;
            }
            envelopeBuilder.field(fieldName, fieldSchema);
        }
        envelopeBuilder.name(schemaNameValidator.validate(newTopicName + ".Envelope"));

        newEnvelopeSchema = envelopeBuilder.build();
        envelopeSchemaUpdateCache.put(oldEnvelopeSchema, newEnvelopeSchema);
        return newEnvelopeSchema;
    }

    private Struct updateEnvelope(Schema newEnvelopeSchema, Struct oldEnvelope) {
        final Struct newEnvelope = new Struct(newEnvelopeSchema);
        final Schema newValueSchema = newEnvelopeSchema.field(Envelope.FieldName.BEFORE).schema();
        for (org.apache.kafka.connect.data.Field field : oldEnvelope.schema().fields()) {
            final String fieldName = field.name();
            Object fieldValue = oldEnvelope.get(field);
            if ((fieldName == Envelope.FieldName.BEFORE || fieldName == Envelope.FieldName.AFTER) &&
                    fieldValue != null) {
                fieldValue = updateValue(newValueSchema, requireStruct(fieldValue, "Updating schema"));
            }
            newEnvelope.put(fieldName, fieldValue);
        }

        return newEnvelope;
    }

    private Struct updateValue(Schema newValueSchema, Struct oldValue) {
        final Struct newValue = new Struct(newValueSchema);
        for (org.apache.kafka.connect.data.Field field : oldValue.schema().fields()) {
            newValue.put(field.name(), oldValue.get(field));
        }
        return newValue;
    }

    private SchemaBuilder copySchemaExcludingName(Schema source, SchemaBuilder builder) {
        return copySchemaExcludingName(source, builder, true);
    }

    private SchemaBuilder copySchemaExcludingName(Schema source, SchemaBuilder builder, boolean copyFields) {
        builder.version(source.version());
        builder.doc(source.doc());
        Map params = source.parameters();
        if (params != null) {
            builder.parameters(params);
        }
        if (source.isOptional()) {
            builder.optional();
        } else {
            builder.required();
        }

        if (copyFields) {
            for (org.apache.kafka.connect.data.Field field : source.fields()) {
                builder.field(field.name(), field.schema());
            }
        }

        return builder;
    }

    private static int validateKeyFieldReplacement(Configuration config, Field field, Field.ValidationOutput problems) {
        String keyFieldRegex = config.getString(KEY_FIELD_REGEX);
        if (keyFieldRegex != null) {
            keyFieldRegex = keyFieldRegex.trim();
        }
        String keyFieldReplacement = config.getString(KEY_FIELD_REPLACEMENT);
        if (keyFieldReplacement != null) {
            keyFieldReplacement = keyFieldReplacement.trim();
        }

        if (keyFieldRegex != null && keyFieldRegex != "") {
            if (keyFieldReplacement == null || keyFieldReplacement == "") {
                problems.accept(KEY_FIELD_REPLACEMENT, keyFieldReplacement,
                        KEY_FIELD_REPLACEMENT.name() + " must be specified if " +
                                KEY_FIELD_REGEX.name() + " is specified");
                return 1;
            }
        }
        return 0;
    }

}
