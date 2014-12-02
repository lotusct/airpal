package com.airbnb.airpal.core.execution;

import com.airbnb.airpal.api.Job;
import com.airbnb.airpal.api.JobState;
import com.airbnb.airpal.api.event.JobUpdateEvent;
import com.airbnb.airpal.api.output.PersistentJobOutput;
import com.airbnb.airpal.core.Persistor;
import com.airbnb.airpal.presto.QueryInfoClient;
import com.airbnb.airpal.presto.QueryRunner;
import com.airbnb.airpal.presto.Table;
import com.airbnb.airpal.presto.hive.HiveColumn;
import com.airbnb.airpal.presto.metadata.ColumnCache;
import com.facebook.presto.client.Column;
import com.facebook.presto.client.ErrorLocation;
import com.facebook.presto.client.FailureInfo;
import com.facebook.presto.client.QueryError;
import com.facebook.presto.client.QueryResults;
import com.facebook.presto.client.StatementClient;
import com.facebook.presto.execution.QueryStats;
import com.facebook.presto.sql.parser.ParsingException;
import com.google.common.base.Predicate;
import com.google.common.base.Splitter;
import com.google.common.base.Stopwatch;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterables;
import com.google.common.eventbus.EventBus;
import com.google.common.util.concurrent.RateLimiter;
import io.airlift.units.DataSize;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.joda.time.DateTime;
import org.joda.time.Duration;

import javax.annotation.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import static com.airbnb.airpal.core.execution.ExecutionClient.ExecutionFailureException;
import static com.airbnb.airpal.presto.QueryInfoClient.BasicQueryInfo;
import static java.lang.String.format;

@Slf4j
@RequiredArgsConstructor
public class Execution implements Callable<Job>
{
    @Getter
    private final Job job;
    @Getter
    private final EventBus eventBus;
    @Getter
    private final QueryRunner queryRunner;
    @Getter
    private final QueryInfoClient queryInfoClient;
    @Getter
    private final QueryExecutionAuthorizer authorizer;
    @Getter
    private final Duration timeout;
    private final ColumnCache columnCache;
    private final RateLimiter updateLimiter = RateLimiter.create(2.0);
    private final int maxRowsPreviewOutput = 1_000;
    private boolean isCancelled = false;

    public void cancel()
    {
        isCancelled = true;
    }

    @RequiredArgsConstructor
    private static class StringContainsSubstringPredicate implements Predicate<String>
    {
        private final String referenceString;

        @Override
        public boolean apply(@Nullable String input)
        {
            if ((input == null) || (referenceString == null)) {
                return false;
            }

            return referenceString.contains(input);
        }
    }

    @Override
    public Job call() throws Exception
    {
        int retries = 20;
        int attempts = 0;
        Set<String> retryableExceptions = ImmutableSet.of(RuntimeException.class.getName());
        Set<String> retryableExceptionMessages = ImmutableSet.of(
                "Expected response code",
                "noMoreSplits"
        );

        while (!isCancelled) {
            attempts++;
            try {
                return doExecute();
            } catch (ExecutionFailureException e) {
                QueryError error = e.getJob().getError();
                FailureInfo failureInfo = (error == null) ? null : error.getFailureInfo();

                if ((failureInfo == null) ||
                        !retryableExceptions.contains(failureInfo.getType()) ||
                        !Iterables.any(retryableExceptionMessages, new StringContainsSubstringPredicate(failureInfo.getMessage())) ||
                        !(retries > attempts)) {
                    throw e;
                }

                job.setError(null);
                job.setState(JobState.QUEUED);
                updateJobInfo(job.getTablesUsed(), job.getColumns(), job.getQueryStats(), null, null, Collections.<List<Object>>emptyList(), true);
                log.info("Retrying request after error {}", error.toString());
            }
        }

        return job;
    }

    private Job doExecute()
            throws ExecutionFailureException
    {
        final String userQuery = QUERY_SPLITTER.splitToList(getJob().getQuery()).get(0);
        final PersistentJobOutput output = job.getOutput();
        final String query = output.processQuery(userQuery);
        final Persistor persistor = output.getPersistor(job);

        if (!persistor.canPersist(authorizer)) {
            throw new ExecutionFailureException(job, "Cannot write tables", null);
        }

        final Stopwatch stopwatch = Stopwatch.createStarted();
        final List<List<Object>> outputPreview = new ArrayList<>(maxRowsPreviewOutput);
        Set<Table> tables = Collections.emptySet();

        try {
            tables = authorizer.tablesUsedByQuery(query);
        } catch (ParsingException e) {
            job.setQueryStats(createNoOpQueryStats());
            job.setError(new QueryError(e.getMessage(), null, -1, new ErrorLocation(e.getLineNumber(), e.getColumnNumber()), null));
            cancelAndThrow(null, stopwatch, new ExecutionFailureException(job, "Invalid query, could not parse", e));
        }

        if (!authorizer.isAuthorizedRead(tables)) {
            job.setQueryStats(createNoOpQueryStats());
            cancelAndThrow(null, stopwatch, new ExecutionFailureException(job, "Cannot access tables", null));
        }

        try (StatementClient client = queryRunner.startInternalQuery(query)) {
            while (client.isValid() && !Thread.currentThread().isInterrupted()) {
                QueryResults results = client.current();
                List<Column> resultColumns = null;
                JobState jobState = null;
                QueryError queryError = null;
                QueryStats queryStats = null;

                if (isCancelled) {
                    cancelAndThrow(client,
                            stopwatch,
                            new ExecutionFailureException(job,
                                    "Query was cancelled",
                                    null));
                }

                if (stopwatch.elapsed(TimeUnit.MILLISECONDS) > timeout.getMillis()) {
                    cancelAndThrow(client,
                            stopwatch,
                            new ExecutionFailureException(job,
                                    format("Query exceeded maximum execution time of %s", timeout.toString()),
                                    null));
                }

                if (results.getError() != null) {
                    queryError = results.getError();
                    jobState = JobState.FAILED;
                }

                if ((results.getInfoUri() != null) && (jobState != JobState.FAILED)) {
                    BasicQueryInfo queryInfo = queryInfoClient.from(results.getInfoUri());

                    if (queryInfo != null) {
                        queryStats = queryInfo.getQueryStats();
                    }
                }

                if (results.getStats() != null) {
                    jobState = JobState.fromStatementState(results.getStats().getState());
                }

                if (results.getColumns() != null) {
                    resultColumns = results.getColumns();
                    persistor.onColumns(resultColumns);
                }

                if (results.getData() != null) {
                    List<List<Object>> resultsData = ImmutableList.copyOf(results.getData());
                    persistor.onData(resultsData);

                    /*
                    int remainingCapacity = maxRowsPreviewOutput - outputPreview.size();
                    if (remainingCapacity > 0) {
                        outputPreview.addAll(resultsData.subList(0, Math.min(remainingCapacity, resultsData.size()) - 1));
                    }
                    */
                }

                rlUpdateJobInfo(tables, resultColumns, queryStats, jobState, queryError, outputPreview);
                client.advance();
            }

            QueryResults finalResults = client.finalResults();
            if (finalResults != null && finalResults.getInfoUri() != null) {
                BasicQueryInfo queryInfo = queryInfoClient.from(finalResults.getInfoUri());

                if (queryInfo != null) {
                    updateJobInfo(
                            null,
                            null,
                            queryInfo.getQueryStats(),
                            JobState.fromStatementState(finalResults.getStats().getState()),
                            finalResults.getError(),
                            outputPreview,
                            true);
                }
            }
        }

        stopwatch.stop();

        if (job.getState() != JobState.FAILED) {
            persistor.persist();
        } else {
            throw new ExecutionFailureException(job, null, null);
        }

        System.out.println("Posting update event because finished");

        return getJob();
    }

    private Set<Table> getTablesWithoutPartitions(Set<Table> tables)
    {
        ImmutableSet.Builder<Table> tablesWithoutPartitions = ImmutableSet.builder();

        if (tables == null || tables.isEmpty()) {
            return Collections.emptySet();
        }

        for (Table table : tables) {
            try {
                List<HiveColumn> allColumns = columnCache.getColumns(table.getSchema(), table.getTable());
                for (HiveColumn column : allColumns) {
                    if (column.isPartition() && !table.getColumns().contains(column.getName())) {
                        tablesWithoutPartitions.add(table);
                    }
                }
            }
            catch (ExecutionException e) {
                e.printStackTrace();
            }
        }

        return tablesWithoutPartitions.build();
    }

    private static void cancelAndThrow(StatementClient client, Stopwatch stopwatch, ExecutionFailureException e)
            throws ExecutionFailureException
    {
        try {
            if (client != null && !client.cancelLeafStage()) {
                client.close();
            }
        }
        catch (IllegalStateException ignored) {
            // Client is already closed.
        }
        catch (RuntimeException ignored) {
            client.close();
        }

        stopwatch.stop();

        throw e;
    }

    private static final Splitter QUERY_SPLITTER = Splitter.on(";").omitEmptyStrings().trimResults();

    /**
     * Rate Limited updateJobInfo
     */
    protected void rlUpdateJobInfo(
            Set<Table> usedTables,
            List<Column> columns,
            QueryStats queryStats,
            JobState state,
            QueryError error,
            List<List<Object>> outputPreview)
    {
        if (updateLimiter.tryAcquire(1)) {
            updateJobInfo(usedTables, columns, queryStats, state, error, outputPreview, true);
        } else {
            updateJobInfo(usedTables, columns, queryStats, state, error, outputPreview, false);
        }
    }

    protected void updateJobInfo(
            Set<Table> usedTables,
            List<Column> columns,
            QueryStats queryStats,
            JobState state,
            QueryError error,
            List<List<Object>> outputPreview,
            boolean postUpdate)
    {
        if ((usedTables != null) && (usedTables.size() > 0)) {
            job.getTablesUsed().addAll(usedTables);
        }

        if ((columns != null) && (columns.size() > 0)) {
            job.setColumns(columns);
        }

        if (queryStats != null) {
            job.setQueryStats(queryStats);
        }

        if ((state != null) && (job.getState() != JobState.FINISHED) && (job.getState() != JobState.FAILED)) {
            job.setState(state);
        }

        if (error != null) {
            FailureInfo failureInfo = new FailureInfo(
                    error.getFailureInfo().getType(),
                    error.getFailureInfo().getMessage(),
                    null,
                    Collections.<FailureInfo>emptyList(),
                    Collections.<String>emptyList(),
                    error.getFailureInfo().getErrorLocation());

            QueryError queryError = new QueryError(
                    error.getMessage(),
                    error.getSqlState(),
                    error.getErrorCode(),
                    error.getErrorLocation(),
                    failureInfo);

            job.setError(queryError);
        }

        if (postUpdate) {
            eventBus.post(new JobUpdateEvent(job, outputPreview));
        }
    }

    public static QueryStats createNoOpQueryStats()
    {
        DateTime now = DateTime.now();
        io.airlift.units.Duration zeroDuration = new io.airlift.units.Duration(0, TimeUnit.SECONDS);
        DataSize zeroData = new DataSize(0, DataSize.Unit.BYTE);

        return new QueryStats(
                now,
                null,
                now,
                now,
                zeroDuration,
                zeroDuration,
                zeroDuration,
                zeroDuration,
                zeroDuration,
                0,
                0,
                0,
                0,
                0,
                0,
                0,
                zeroData,
                zeroDuration,
                zeroDuration,
                zeroDuration,
                zeroDuration,
                zeroData,
                0,
                zeroData,
                0,
                zeroData,
                0
        );
    }
}