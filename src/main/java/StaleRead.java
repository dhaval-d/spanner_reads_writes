/*
 * Copyright 2018 Google Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

// Imports the Google Cloud client library
import com.google.cloud.spanner.*;

import java.io.*;
import java.util.concurrent.TimeUnit;

import static com.google.cloud.spanner.TransactionRunner.TransactionCallable;

import java.util.ArrayList;


import io.opencensus.common.Scope;
import io.opencensus.contrib.grpc.metrics.RpcViews;
import io.opencensus.exporter.stats.stackdriver.StackdriverStatsExporter;
//import io.opencensus.exporter.trace.stackdriver.StackdriverExporter;
import io.opencensus.exporter.trace.stackdriver.StackdriverTraceExporter;
import io.opencensus.exporter.trace.stackdriver.StackdriverTraceConfiguration;
import io.opencensus.trace.Tracing;
import io.opencensus.trace.samplers.Samplers;
import io.opencensus.trace.Tracer;
import java.util.concurrent.Callable;

import java.util.Arrays;


public class StaleRead implements Callable<Long>  {
    private Tracer tracer;
    private String keyField;
    private DatabaseClient dbClient;
    private int taskId;
    private Spanner spanner;
    private SpannerOptions options;

    public StaleRead(Tracer tracer, String keyField, DatabaseClient dbClient, int taskId){
        this.tracer = tracer;
        this.keyField = keyField;
        this.dbClient = dbClient;
        this.taskId = taskId;
    }

    public StaleRead(Tracer tracer, String keyField, Spanner spanner,SpannerOptions options, int taskId){
        this.tracer = tracer;
        this.keyField = keyField;
        this.taskId = taskId;

        this.spanner = spanner;
        this.options = options;
        dbClient = createDbClient();


    }

    @Override
    public Long call() {
        long start_time = System.currentTimeMillis();
        try {

            performStaleRead();

        } catch(Exception ex) {
                System.out.println(ex.getMessage()+" - Inside Call()");
        }
        return System.currentTimeMillis() -start_time;
    }

    // Perform a stale read with exact staleness of 15 seconds
    protected void performStaleRead() throws Exception{

        try (Scope ss = tracer
                .spanBuilder("Stale_read" +" - " + Integer.toString(this.taskId))
                // Enable the trace sampler.
                // We are always sampling for demo purposes only: this is a very high sampling
                // rate, but sufficient for the purpose of this quick demo.
                // More realistically perhaps tracing 1 in 10,000 might be more useful
                .setSampler(Samplers.alwaysSample())
                .startScopedSpan()) {
            Statement statement = getQueryStatement(keyField);

            // Queries the database
            try(ResultSet resultSet = dbClient
                    .singleUse(TimestampBound.ofExactStaleness(15, TimeUnit.SECONDS))
                    .executeQuery(statement)){
                tracer.getCurrentSpan().addAnnotation("Executed Query");
                processResults(keyField, resultSet);
            } finally {
                tracer.getCurrentSpan().addAnnotation("Closed Results");
            }

        }
        finally {
        }
    }

    // create database client
    private DatabaseClient createDbClient() {
        DatabaseClient client = spanner.getDatabaseClient(DatabaseId.of(
                options.getProjectId(), "instance-1", "db1"));
        tracer.getCurrentSpan().addAnnotation("Created DbClient");
        return client;
    }


    // Open resultSet and confirm a match with key else throw an exception
    private void processResults(String keyField, ResultSet resultSet) throws Exception {
        while (resultSet.next()) {
            String result = resultSet.getString(0);
            // match found
            if(result.equals(keyField)){
                break;
            } else {
                throw new Exception("Records did not match");
            }
        }
    }

    // Build Query for Spanner
    protected Statement getQueryStatement(String keyField) {
        Statement statement = Statement
                .newBuilder("SELECT pk_field FROM table1 where pk_field= @KEY_FIELD")
                .bind("KEY_FIELD").to(keyField)
                .build();
        tracer.getCurrentSpan().addAnnotation("Created Statement");
        return statement;
    }




}
