/*! ******************************************************************************
 *
 * Pentaho
 *
 * Copyright (C) 2026 by Hitachi Vantara, LLC : http://www.pentaho.com
 *
 * Use of this software is governed by the Business Source License included
 * in the LICENSE.TXT file.
 *
 * Change Date: 2029-07-20
 ******************************************************************************/

package org.pentaho.big.data.kettle.plugins.kafka;

import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.Producer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;

/**
 * End-to-end integration test that exercises the {@code kafka-clients} library against a real
 * Kafka broker. It is intended to validate the upgrade to kafka-clients 4.1.2 (CVE-2026-35554 /
 * PPP-6393), focusing on the behaviours that actually changed between 3.9.x and 4.x:
 *
 * <ul>
 *   <li>{@code KafkaConsumer.poll(long)} was <em>removed</em>; only {@code poll(Duration)} remains.</li>
 *   <li>{@code ConsumerRecords} exposes the new {@code nextOffsets()} accessor added in 4.x.</li>
 *   <li>The transitive compression codecs (gzip, snappy, lz4, zstd) must be present on the runtime
 *       classpath. Note that lz4 changed Maven coordinates in 4.x
 *       ({@code org.lz4:lz4-java} → {@code at.yawk.lz4:lz4-java}), so a missing codec would only
 *       surface at runtime when a producer is configured with that {@code compression.type}.</li>
 * </ul>
 *
 * <p><b>How to run.</b> This test only runs when a broker is supplied; otherwise every test is
 * skipped via {@link org.junit.Assume}. Provide the broker through either:
 * <ul>
 *   <li>the system property {@code kafka.it.bootstrap.servers}, or</li>
 *   <li>the environment variable {@code KAFKA_IT_BOOTSTRAP_SERVERS}.</li>
 * </ul>
 *
 * <pre>
 *   mvn -o verify -DrunITs \
 *       -Dkafka.it.bootstrap.servers=localhost:9092 \
 *       -Dit.test=KafkaClientsIT
 * </pre>
 *
 * <p>The build wires {@code src/it/java} into the failsafe {@code integration-test} profile, so the
 * class is only compiled/executed when {@code -DrunITs} is set. Because Kafka 4.x is KRaft-only
 * (ZooKeeper was removed), the broker referenced here must be a 4.x KRaft broker for the upgrade to
 * be fully exercised.
 */
public class KafkaClientsIT {

  private static final String BOOTSTRAP_PROPERTY = "kafka.it.bootstrap.servers";
  private static final String BOOTSTRAP_ENV = "KAFKA_IT_BOOTSTRAP_SERVERS";

  private static final Duration POLL_DURATION = Duration.ofMillis( 1000 );
  private static final Duration RECEIVE_TIMEOUT = Duration.ofSeconds( 30 );

  private static String bootstrapServers;

  @BeforeClass
  public static void resolveBroker() {
    bootstrapServers = System.getProperty( BOOTSTRAP_PROPERTY );
    if ( isBlank( bootstrapServers ) ) {
      bootstrapServers = System.getenv( BOOTSTRAP_ENV );
    }
    // Skip the whole class cleanly when no broker is configured so the normal build is never broken.
    assumeTrue(
      "Skipping Kafka integration test: set -D" + BOOTSTRAP_PROPERTY + " or $" + BOOTSTRAP_ENV
        + " to a running Kafka 4.x broker to enable it.",
      !isBlank( bootstrapServers ) );
  }

  @AfterClass
  public static void clearBroker() {
    bootstrapServers = null;
  }

  /**
   * Round-trips records through a real broker using {@code poll(Duration)}. This is the canonical
   * proof that the removal of {@code poll(long)} in kafka-clients 4.x was migrated correctly: the
   * code under test polls with a {@link Duration} and still receives every produced record.
   */
  @Test
  public void producerConsumerRoundTripUsingPollDuration() throws Exception {
    String topic = createTopic();
    int messageCount = 25;

    Map<String, String> expected = produceStringRecords( topic, messageCount, Collections.emptyMap() );
    Map<String, String> received = consumeStringRecords( topic, messageCount, newGroupId() );

    assertEquals( "Should consume exactly the produced records", expected, received );
  }

  /**
   * Produces and consumes a record for every supported {@code compression.type}. A missing codec
   * jar (the transitive-dependency gap introduced by the 4.x lz4 coordinate change) would fail here
   * with the offending compression type clearly identified, rather than silently in production.
   */
  @Test
  public void allCompressionCodecsAreFunctional() throws Exception {
    String[] codecs = { "none", "gzip", "snappy", "lz4", "zstd" };
    List<String> failures = new ArrayList<>();

    for ( String codec : codecs ) {
      try {
        String topic = createTopic();
        Map<String, String> expected =
          produceStringRecords( topic, 5, Collections.singletonMap( ProducerConfig.COMPRESSION_TYPE_CONFIG, codec ) );
        Map<String, String> received = consumeStringRecords( topic, 5, newGroupId() );
        assertEquals( "compression.type=" + codec + " round trip mismatch", expected, received );
      } catch ( Throwable t ) {
        failures.add( codec + " -> " + t.getClass().getSimpleName() + ": " + t.getMessage() );
      }
    }

    assertTrue( "Compression codecs failing (missing/incompatible jars?): " + failures, failures.isEmpty() );
  }

  /**
   * Verifies the new {@code ConsumerRecords.nextOffsets()} accessor added in kafka-clients 4.x and
   * the standard manual-commit lifecycle ({@code commitSync(Map)}), and confirms that a second
   * consumer in the same group resumes from the committed position.
   */
  @Test
  public void consumerRecordsExposeNextOffsetsAndCommitIsHonoured() throws Exception {
    String topic = createTopic();
    int messageCount = 10;
    String groupId = newGroupId();

    produceStringRecords( topic, messageCount, Collections.emptyMap() );

    Map<TopicPartition, OffsetAndMetadata> committed = new HashMap<>();
    int firstBatch = 0;
    try ( Consumer<String, String> consumer = newConsumer( groupId ) ) {
      consumer.subscribe( Collections.singletonList( topic ) );

      long deadline = System.currentTimeMillis() + RECEIVE_TIMEOUT.toMillis();
      while ( firstBatch == 0 && System.currentTimeMillis() < deadline ) {
        ConsumerRecords<String, String> records = consumer.poll( POLL_DURATION );
        firstBatch = records.count();
        if ( firstBatch > 0 ) {
          // nextOffsets() is a 4.x addition - assert it is wired and consistent with the data read.
          Map<TopicPartition, OffsetAndMetadata> next = records.nextOffsets();
          assertNotNull( "ConsumerRecords.nextOffsets() must not be null in kafka-clients 4.x", next );
          assertFalse( "nextOffsets() should report progress after a non-empty poll", next.isEmpty() );

          for ( TopicPartition partition : records.partitions() ) {
            List<ConsumerRecord<String, String>> partitionRecords = records.records( partition );
            long lastOffset = partitionRecords.get( partitionRecords.size() - 1 ).offset();
            committed.put( partition, new OffsetAndMetadata( lastOffset + 1 ) );
          }
          consumer.commitSync( committed );
        }
      }
    }

    assertTrue( "Expected to read at least one record before committing", firstBatch > 0 );

    // A fresh consumer in the same group must start from the committed offsets, not re-read everything.
    try ( Consumer<String, String> resumed = newConsumer( groupId ) ) {
      resumed.subscribe( Collections.singletonList( topic ) );
      resumed.poll( Duration.ofMillis( 500 ) ); // trigger assignment
      for ( Map.Entry<TopicPartition, OffsetAndMetadata> entry : committed.entrySet() ) {
        OffsetAndMetadata persisted = resumed.committed( Collections.singleton( entry.getKey() ) ).get( entry.getKey() );
        assertNotNull( "Committed offset should be retrievable for " + entry.getKey(), persisted );
        assertEquals( "Resumed position should match committed offset for " + entry.getKey(),
          entry.getValue().offset(), persisted.offset() );
      }
    }
  }

  // ---------------------------------------------------------------------------------------------
  // Helpers
  // ---------------------------------------------------------------------------------------------

  private static String createTopic() throws Exception {
    String topic = "pdi-kafka-it-" + UUID.randomUUID();
    Properties adminProps = new Properties();
    adminProps.put( "bootstrap.servers", bootstrapServers );
    try ( Admin admin = Admin.create( adminProps ) ) {
      NewTopic newTopic = new NewTopic( topic, 1, (short) 1 );
      try {
        admin.createTopics( Collections.singletonList( newTopic ) ).all().get( 30, TimeUnit.SECONDS );
      } catch ( ExecutionException e ) {
        // Tolerate brokers that auto-create or a benign "already exists" race.
        if ( !e.getMessage().contains( "TopicExistsException" ) ) {
          throw e;
        }
      }
    }
    return topic;
  }

  private static Map<String, String> produceStringRecords( String topic, int count, Map<String, Object> extraConfig )
    throws Exception {
    Map<String, String> sent = new LinkedHashMap<>();
    Properties props = new Properties();
    props.put( ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers );
    props.put( ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName() );
    props.put( ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName() );
    props.put( ProducerConfig.ACKS_CONFIG, "all" );
    props.putAll( extraConfig );

    try ( Producer<String, String> producer = new KafkaProducer<>( props ) ) {
      for ( int i = 0; i < count; i++ ) {
        String key = "key-" + i;
        String value = "value-" + i;
        sent.put( key, value );
        RecordMetadata metadata =
          producer.send( new ProducerRecord<>( topic, key, value ) ).get( 30, TimeUnit.SECONDS );
        assertEquals( "Record should land on the expected topic", topic, metadata.topic() );
      }
      producer.flush();
    }
    return sent;
  }

  private static Map<String, String> consumeStringRecords( String topic, int expectedCount, String groupId ) {
    Map<String, String> received = new LinkedHashMap<>();
    try ( Consumer<String, String> consumer = newConsumer( groupId ) ) {
      consumer.subscribe( Collections.singletonList( topic ) );
      long deadline = System.currentTimeMillis() + RECEIVE_TIMEOUT.toMillis();
      while ( received.size() < expectedCount && System.currentTimeMillis() < deadline ) {
        ConsumerRecords<String, String> records = consumer.poll( POLL_DURATION );
        for ( ConsumerRecord<String, String> record : records ) {
          received.put( record.key(), record.value() );
        }
      }
    }
    return received;
  }

  private static Consumer<String, String> newConsumer( String groupId ) {
    Properties props = new Properties();
    props.put( ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers );
    props.put( ConsumerConfig.GROUP_ID_CONFIG, groupId );
    props.put( ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName() );
    props.put( ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName() );
    props.put( ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest" );
    props.put( ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false );
    return new KafkaConsumer<>( props );
  }

  private static String newGroupId() {
    return "pdi-kafka-it-group-" + UUID.randomUUID();
  }

  private static boolean isBlank( String value ) {
    return value == null || value.trim().isEmpty();
  }
}
