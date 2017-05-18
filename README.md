# kudu-flume-sink
kudu-flume-sink with csv splitter 

tier1.sinks.k50.type=org.apache.kudu.flume.sink.KuduSink
tier1.sinks.k50.masterAddresses=master1
tier1.sinks.k50.tableName=impala::default.table1
tier1.sinks.k50.channel=channel-1
tier1.sinks.k50.batchSize=100
tier1.sinks.k50.producer=org.apache.kudu.flume.sink.SplitterKuduOperationsProducer
tier1.sinks.k50.producer.delimited=,
tier1.sinks.k50.producer.fields=A,B,C,D
