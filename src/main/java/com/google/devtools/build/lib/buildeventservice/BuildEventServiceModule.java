// Copyright 2017 The Bazel Authors. All rights reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//    http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.
package com.google.devtools.build.lib.buildeventservice;


import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Preconditions;
import com.google.common.base.Strings;
import com.google.common.base.Supplier;
import com.google.common.base.Suppliers;
import com.google.common.base.Throwables;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.flogger.GoogleLogger;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.MoreExecutors;
import com.google.common.util.concurrent.ThreadFactoryBuilder;
import com.google.common.util.concurrent.Uninterruptibles;
import com.google.devtools.build.lib.authandtls.AuthAndTLSOptions;
import com.google.devtools.build.lib.buildeventservice.BuildEventServiceOptions.BesUploadMode;
import com.google.devtools.build.lib.buildeventservice.client.BuildEventServiceClient;
import com.google.devtools.build.lib.buildeventstream.AnnounceBuildEventTransportsEvent;
import com.google.devtools.build.lib.buildeventstream.BuildEventArtifactUploader;
import com.google.devtools.build.lib.buildeventstream.BuildEventProtocolOptions;
import com.google.devtools.build.lib.buildeventstream.BuildEventStreamProtos.Aborted.AbortReason;
import com.google.devtools.build.lib.buildeventstream.BuildEventTransport;
import com.google.devtools.build.lib.buildeventstream.BuildEventTransportClosedEvent;
import com.google.devtools.build.lib.buildeventstream.LocalFilesArtifactUploader;
import com.google.devtools.build.lib.buildeventstream.transports.BinaryFormatFileTransport;
import com.google.devtools.build.lib.buildeventstream.transports.BuildEventStreamOptions;
import com.google.devtools.build.lib.buildeventstream.transports.JsonFormatFileTransport;
import com.google.devtools.build.lib.buildeventstream.transports.TextFormatFileTransport;
import com.google.devtools.build.lib.events.Event;
import com.google.devtools.build.lib.events.EventHandler;
import com.google.devtools.build.lib.events.Reporter;
import com.google.devtools.build.lib.profiler.AutoProfiler;
import com.google.devtools.build.lib.runtime.BlazeCommandEventHandler;
import com.google.devtools.build.lib.runtime.BlazeModule;
import com.google.devtools.build.lib.runtime.BuildEventStreamer;
import com.google.devtools.build.lib.runtime.CommandEnvironment;
import com.google.devtools.build.lib.runtime.CountingArtifactGroupNamer;
import com.google.devtools.build.lib.runtime.SynchronizedOutputStream;
import com.google.devtools.build.lib.util.AbruptExitException;
import com.google.devtools.build.lib.util.ExitCode;
import com.google.devtools.build.lib.util.LoggingUtil;
import com.google.devtools.build.lib.util.io.OutErr;
import com.google.devtools.common.options.OptionsBase;
import com.google.devtools.common.options.OptionsParsingException;
import com.google.devtools.common.options.OptionsParsingResult;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.time.Duration;
import java.time.Instant;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.annotation.Nullable;

/**
 * Module responsible for the Build Event Transport (BEP) and Build Event Service (BES)
 * functionality.
 */
public abstract class BuildEventServiceModule<BESOptionsT extends BuildEventServiceOptions>
    extends BlazeModule {

  private static final Logger logger = Logger.getLogger(BuildEventServiceModule.class.getName());
  private static final GoogleLogger googleLogger = GoogleLogger.forEnclosingClass();

  private BuildEventProtocolOptions bepOptions;
  private AuthAndTLSOptions authTlsOptions;
  private BuildEventStreamOptions besStreamOptions;
  private boolean useExperimentalUi;
  /** Holds the close futures for the upload of each transport */
  private ImmutableMap<BuildEventTransport, ListenableFuture<Void>> closeFuturesMap =
      ImmutableMap.of();

  /**
   * Holds the half-close futures for the upload of each transport. The completion of the half-close
   * indicates that the client has sent all of the data to the server and is just waiting for
   * acknowledgement. The client must still keep the data buffered locally in case acknowledgement
   * fails.
   */
  private ImmutableMap<BuildEventTransport, ListenableFuture<Void>> halfCloseFuturesMap =
      ImmutableMap.of();

  // TODO(lpino): Use Optional instead of @Nullable for the members below.
  @Nullable private OutErr outErr;
  @Nullable private ImmutableSet<BuildEventTransport> bepTransports;
  @Nullable private String buildRequestId;
  @Nullable private String invocationId;
  @Nullable private Reporter cmdLineReporter;
  @Nullable private BuildEventStreamer streamer;

  protected BESOptionsT besOptions;

  protected void reportCommandLineError(EventHandler commandLineReporter, Exception exception) {
    // Don't hide unchecked exceptions as part of the error reporting.
    Throwables.throwIfUnchecked(exception);
    commandLineReporter.handle(Event.error(exception.getMessage()));
  }

  /** Maximum duration Bazel waits for the previous invocation to finish before cancelling it. */
  protected Duration getMaxWaitForPreviousInvocation() {
    return Duration.ofSeconds(5);
  }

  /** Report errors in the command line and possibly fail the build. */
  private void reportError(
      EventHandler commandLineReporter,
      ModuleEnvironment moduleEnvironment,
      String msg,
      Exception exception,
      ExitCode exitCode) {
    // Don't hide unchecked exceptions as part of the error reporting.
    Throwables.throwIfUnchecked(exception);

    googleLogger.atSevere().withCause(exception).log(msg);
    AbruptExitException abruptException = new AbruptExitException(msg, exitCode, exception);
    reportCommandLineError(commandLineReporter, exception);
    moduleEnvironment.exit(abruptException);
  }

  @Override
  public Iterable<Class<? extends OptionsBase>> getCommonCommandOptions() {
    return ImmutableList.of(
        optionsClass(),
        AuthAndTLSOptions.class,
        BuildEventStreamOptions.class,
        BuildEventProtocolOptions.class);
  }

  private void cancelPendingUploads() {
    closeFuturesMap
        .values()
        .forEach(closeFuture -> closeFuture.cancel(/* mayInterruptIfRunning= */ true));
    closeFuturesMap = ImmutableMap.of();
    halfCloseFuturesMap = ImmutableMap.of();
  }

  private static boolean isTimeoutException(ExecutionException e) {
    return e.getCause() instanceof TimeoutException;
  }

  private void waitForPreviousInvocation() {
    if (closeFuturesMap.isEmpty()) {
      return;
    }

    try {
      // TODO(b/234994611): Find a way to print a meaningful message when waiting. The current
      // infrastructure doesn't allow printing messages in the terminal in beforeCommand.
      ImmutableMap<BuildEventTransport, ListenableFuture<Void>> futureMap =
          besOptions.besUploadMode == BesUploadMode.FULLY_ASYNC
              ? halfCloseFuturesMap
              : closeFuturesMap;
      Uninterruptibles.getUninterruptibly(
          Futures.allAsList(futureMap.values()),
          getMaxWaitForPreviousInvocation().getSeconds(),
          TimeUnit.SECONDS);
    } catch (TimeoutException exception) {
      String msg =
          String.format(
              "Pending Build Event Protocol upload took more than %ds to finish. "
                  + "Cancelling and starting a new invocation...",
              getMaxWaitForPreviousInvocation().getSeconds());
      cmdLineReporter.handle(Event.warn(msg));
      googleLogger.atWarning().withCause(exception).log(msg);
    } catch (ExecutionException e) {
      // Futures.withTimeout wraps the TimeoutException in an ExecutionException when the future
      // times out.
      String previousExceptionMsg =
          isTimeoutException(e) ? "The Build Event Protocol upload timed out" : e.getMessage();
      String msg =
          String.format(
              "Previous invocation failed to finish Build Event Protocol upload "
                  + "with the following exception: '%s'. "
                  + "Ignoring the failure and starting a new invocation...",
              previousExceptionMsg);
      cmdLineReporter.handle(Event.warn(msg));
      googleLogger.atWarning().withCause(e).log(msg);
    } finally {
      cancelPendingUploads();
    }
  }

  @Override
  public void beforeCommand(CommandEnvironment cmdEnv) {
    this.invocationId = cmdEnv.getCommandId().toString();
    this.buildRequestId = cmdEnv.getBuildRequestId();
    this.cmdLineReporter = cmdEnv.getReporter();

    OptionsParsingResult parsingResult = cmdEnv.getOptions();
    this.besOptions = Preconditions.checkNotNull(parsingResult.getOptions(optionsClass()));
    this.bepOptions =
        Preconditions.checkNotNull(parsingResult.getOptions(BuildEventProtocolOptions.class));
    this.authTlsOptions =
        Preconditions.checkNotNull(parsingResult.getOptions(AuthAndTLSOptions.class));
    this.besStreamOptions =
        Preconditions.checkNotNull(parsingResult.getOptions(BuildEventStreamOptions.class));
    this.useExperimentalUi =
        Preconditions.checkNotNull(parsingResult.getOptions(BlazeCommandEventHandler.Options.class))
            .experimentalUi;

    CountingArtifactGroupNamer artifactGroupNamer = new CountingArtifactGroupNamer();
    Supplier<BuildEventArtifactUploader> uploaderSupplier =
        Suppliers.memoize(
            () ->
                cmdEnv
                    .getRuntime()
                    .getBuildEventArtifactUploaderFactoryMap()
                    .select(bepOptions.buildEventUploadStrategy)
                    .create(cmdEnv));

    // We need to wait for the previous invocation before we check the whitelist of commands to
    // allow completing previous runs using BES, for example:
    //   bazel build (..run with async BES..)
    //   bazel info <-- Doesn't run with BES unless we wait before cheking the whitelist.
    waitForPreviousInvocation();

    if (!whitelistedCommands(besOptions).contains(cmdEnv.getCommandName())) {
      // Exit early if the running command isn't supported.
      return;
    }

    bepTransports = createBepTransports(cmdEnv, uploaderSupplier, artifactGroupNamer);
    if (bepTransports.isEmpty()) {
      // Exit early if there are no transports to stream to.
      return;
    }

    streamer =
        new BuildEventStreamer.Builder()
            .buildEventTransports(bepTransports)
            .besStreamOptions(besStreamOptions)
            .artifactGroupNamer(artifactGroupNamer)
            .build();

    cmdEnv.getEventBus().register(streamer);
    registerOutAndErrOutputStreams();

    // This event should probably be posted in a more general place (e.g. {@link BuildTool};
    // however, so far the BES module is the only module that requires extra work after the build
    // so we post it here until it's needed for other modules.
    cmdLineReporter.post(new AnnounceBuildEventTransportsEvent(bepTransports));
  }

  private void registerOutAndErrOutputStreams() {
    int bufferSize = besOptions.besOuterrBufferSize;
    int chunkSize = besOptions.besOuterrChunkSize;
    final SynchronizedOutputStream out = new SynchronizedOutputStream(bufferSize, chunkSize);
    final SynchronizedOutputStream err = new SynchronizedOutputStream(bufferSize, chunkSize);

    this.outErr = OutErr.create(out, err);
    streamer.registerOutErrProvider(
        new BuildEventStreamer.OutErrProvider() {
          @Override
          public Iterable<String> getOut() {
            return out.readAndReset();
          }

          @Override
          public Iterable<String> getErr() {
            return err.readAndReset();
          }
        });
    err.registerStreamer(streamer);
    out.registerStreamer(streamer);
  }

  @Override
  public OutErr getOutputListener() {
    return outErr;
  }

  private void forceShutdownBuildEventStreamer() {
    streamer.close(AbortReason.INTERNAL);
    closeFuturesMap = constructCloseFuturesMapWithTimeouts(streamer.getCloseFuturesMap());
    try {
      // TODO(b/130148250): Uninterruptibles.getUninterruptibly waits forever if no timeout is
      //  passed. We should fix this by waiting at most the value set by bes_timeout.
      googleLogger.atInfo().log("Closing pending build event transports");
      Uninterruptibles.getUninterruptibly(Futures.allAsList(closeFuturesMap.values()));
    } catch (ExecutionException e) {
      googleLogger.atSevere().withCause(e).log("Failed to close a build event transport");
      LoggingUtil.logToRemote(Level.SEVERE, "Failed to close a build event transport", e);
    } finally {
      cancelPendingUploads();
    }
  }

  @Override
  public void blazeShutdownOnCrash() {
    if (streamer != null) {
      googleLogger.atWarning().log("Attempting to close BES streamer on crash");
      forceShutdownBuildEventStreamer();
    }
  }

  @Override
  public void blazeShutdown() {
    if (closeFuturesMap.isEmpty()) {
      return;
    }

    try {
      Uninterruptibles.getUninterruptibly(
          Futures.allAsList(closeFuturesMap.values()),
          getMaxWaitForPreviousInvocation().getSeconds(),
          TimeUnit.SECONDS);
    } catch (TimeoutException | ExecutionException exception) {
      googleLogger.atWarning().withCause(exception).log(
          "Encountered Exception when closing BEP transports in Blaze's shutting down sequence");
    } finally {
      cancelPendingUploads();
    }
  }

  private void reportWaitingForBesMessage(Instant startTime) {
    cmdLineReporter.handle(
        Event.progress(
            "Waiting for Build Event Protocol upload. Waited "
                + Duration.between(startTime, Instant.now()).getSeconds()
                + "s, waiting at most "
                + besOptions.besTimeout.getSeconds()
                + "s."));
  }

  private void waitForBuildEventTransportsToClose() throws AbruptExitException {
    final ScheduledExecutorService executor =
        Executors.newSingleThreadScheduledExecutor(
            new ThreadFactoryBuilder().setNameFormat("bes-notify-ui-%d").build());
    ScheduledFuture<?> waitMessageFuture = null;

    try {
      if (useExperimentalUi) {
        // Notify the UI handler when a transport finished closing.
        closeFuturesMap.forEach(
            (bepTransport, closeFuture) ->
                closeFuture.addListener(
                    () -> {
                      cmdLineReporter.post(new BuildEventTransportClosedEvent(bepTransport));
                    },
                    executor));
      } else {
        cmdLineReporter.handle(Event.progress("Waiting for Build Event Protocol upload..."));
        Instant startTime = Instant.now();
        waitMessageFuture =
            executor.scheduleAtFixedRate(
                () -> reportWaitingForBesMessage(startTime),
                /* initialDelay = */ 0,
                /* period = */ 1,
                TimeUnit.SECONDS);
      }

      try (AutoProfiler p = AutoProfiler.logged("waiting for BES close", logger)) {
        // TODO(b/130148250): Uninterruptibles.getUninterruptibly waits forever if no timeout is
        //  passed. We should fix this by waiting at most the value set by bes_timeout.
        Uninterruptibles.getUninterruptibly(Futures.allAsList(closeFuturesMap.values()));
      }
    } catch (ExecutionException e) {
      // Futures.withTimeout wraps the TimeoutException in an ExecutionException when the future
      // times out.
      if (isTimeoutException(e)) {
        throw new AbruptExitException(
            "The Build Event Protocol upload timed out",
            ExitCode.TRANSIENT_BUILD_EVENT_SERVICE_UPLOAD_ERROR,
            e);
      }

      Throwables.throwIfInstanceOf(e.getCause(), AbruptExitException.class);
      throw new RuntimeException(
          String.format(
              "Unexpected Exception '%s' when closing BEP transports, this is a bug.",
              e.getCause().getMessage()));
    } finally {
      cancelPendingUploads();
      if (waitMessageFuture != null) {
        waitMessageFuture.cancel(/* mayInterruptIfRunning= */ true);
      }
      executor.shutdown();
    }
  }

  private static ImmutableMap<BuildEventTransport, ListenableFuture<Void>>
      constructCloseFuturesMapWithTimeouts(
          ImmutableMap<BuildEventTransport, ListenableFuture<Void>> bepTransportToCloseFuturesMap) {
    ImmutableMap.Builder<BuildEventTransport, ListenableFuture<Void>> builder =
        ImmutableMap.builder();

    bepTransportToCloseFuturesMap.forEach(
        (bepTransport, closeFuture) -> {
          final ListenableFuture<Void> closeFutureWithTimeout;
          if (bepTransport.getTimeout().isZero() || bepTransport.getTimeout().isNegative()) {
            closeFutureWithTimeout = closeFuture;
          } else {
            final ScheduledExecutorService timeoutExecutor =
                Executors.newSingleThreadScheduledExecutor(
                    new ThreadFactoryBuilder()
                        .setNameFormat("bes-close-" + bepTransport.name() + "-%d")
                        .build());

            closeFutureWithTimeout =
                Futures.withTimeout(
                    closeFuture,
                    bepTransport.getTimeout().toMillis(),
                    TimeUnit.MILLISECONDS,
                    timeoutExecutor);
            closeFutureWithTimeout.addListener(
                () -> timeoutExecutor.shutdown(), MoreExecutors.directExecutor());
          }
          builder.put(bepTransport, closeFutureWithTimeout);
        });

    return builder.build();
  }

  private void closeBepTransports() throws AbruptExitException {
    closeFuturesMap = constructCloseFuturesMapWithTimeouts(streamer.getCloseFuturesMap());
    halfCloseFuturesMap = constructCloseFuturesMapWithTimeouts(streamer.getHalfClosedMap());
    switch (besOptions.besUploadMode) {
      case WAIT_FOR_UPLOAD_COMPLETE:
        waitForBuildEventTransportsToClose();
        return;

      case NOWAIT_FOR_UPLOAD_COMPLETE:
      case FULLY_ASYNC:
        // When running asynchronously notify the UI immediately since we won't wait for the
        // uploads to close.
        for (BuildEventTransport bepTransport : bepTransports) {
          cmdLineReporter.post(new BuildEventTransportClosedEvent(bepTransport));
        }
        return;
    }
    throw new IllegalStateException("Unknown BesUploadMode found: " + besOptions.besUploadMode);
  }

  @Override
  public void afterCommand() throws AbruptExitException {
    if (streamer != null) {
      if (!streamer.isClosed()) {
        // This should not occur, but close with an internal error if a {@link BuildEventStreamer}
        // bug manifests as an unclosed streamer.
        googleLogger.atWarning().log("Attempting to close BES streamer after command");
        String msg = "BES was not properly closed";
        LoggingUtil.logToRemote(Level.WARNING, msg, new IllegalStateException(msg));
        forceShutdownBuildEventStreamer();
      }

      closeBepTransports();

      if (!Strings.isNullOrEmpty(besOptions.besBackend)) {
        constructAndMaybeReportInvocationIdUrl();
      } else if (!bepTransports.isEmpty()) {
        cmdLineReporter.handle(Event.info("Build Event Protocol files produced successfully."));
      }
    }

    if (!besStreamOptions.keepBackendConnections) {
      clearBesClient();
    }
  }

  @Override
  public void commandComplete() {
    this.outErr = null;
    this.bepTransports = null;
    this.invocationId = null;
    this.buildRequestId = null;
    this.cmdLineReporter = null;
    this.streamer = null;
  }

  private void constructAndMaybeReportInvocationIdUrl() {
    if (!getInvocationIdPrefix().isEmpty()) {
      cmdLineReporter.handle(
          Event.info("Streaming build results to: " + getInvocationIdPrefix() + invocationId));
    }
  }

  private void constructAndMaybeReportBuildRequestIdUrl() {
    if (!getBuildRequestIdPrefix().isEmpty()) {
      cmdLineReporter.handle(
          Event.info(
              "See "
                  + getBuildRequestIdPrefix()
                  + buildRequestId
                  + " for more information about your request."));
    }
  }

  private void constructAndReportIds() {
    cmdLineReporter.handle(
        Event.info(
            String.format(
                "Streaming Build Event Protocol to '%s' with build_request_id: '%s'"
                    + " and invocation_id: '%s'",
                besOptions.besBackend, buildRequestId, invocationId)));
  }

  @Nullable
  private BuildEventServiceTransport createBesTransport(
      CommandEnvironment cmdEnv,
      Supplier<BuildEventArtifactUploader> uploaderSupplier,
      CountingArtifactGroupNamer artifactGroupNamer) {
    if (Strings.isNullOrEmpty(besOptions.besBackend)) {
      clearBesClient();
      return null;
    }

    constructAndReportIds();

    final BuildEventServiceClient besClient;
    try {
      besClient = getBesClient(besOptions, authTlsOptions);
    } catch (IOException | OptionsParsingException e) {
      reportError(
          cmdLineReporter,
          cmdEnv.getBlazeModuleEnvironment(),
          e.getMessage(),
          e,
          ExitCode.LOCAL_ENVIRONMENTAL_ERROR);
      return null;
    }

    BuildEventServiceProtoUtil besProtoUtil =
        new BuildEventServiceProtoUtil.Builder()
            .buildRequestId(buildRequestId)
            .invocationId(invocationId)
            .projectId(besOptions.projectId)
            .commandName(cmdEnv.getCommandName())
            .keywords(getBesKeywords(besOptions, cmdEnv.getRuntime().getStartupOptionsProvider()))
            .build();

    return new BuildEventServiceTransport.Builder()
        .localFileUploader(uploaderSupplier.get())
        .besClient(besClient)
        .besOptions(besOptions)
        .besProtoUtil(besProtoUtil)
        .artifactGroupNamer(artifactGroupNamer)
        .bepOptions(bepOptions)
        .clock(cmdEnv.getRuntime().getClock())
        .eventBus(cmdEnv.getEventBus())
        .build();
  }

  private ImmutableSet<BuildEventTransport> createBepTransports(
      CommandEnvironment cmdEnv,
      Supplier<BuildEventArtifactUploader> uploaderSupplier,
      CountingArtifactGroupNamer artifactGroupNamer) {
    ImmutableSet.Builder<BuildEventTransport> bepTransportsBuilder = new ImmutableSet.Builder<>();

    if (!Strings.isNullOrEmpty(besStreamOptions.buildEventTextFile)) {
      try {
        BufferedOutputStream bepTextOutputStream =
            new BufferedOutputStream(
                Files.newOutputStream(Paths.get(besStreamOptions.buildEventTextFile)));

        BuildEventArtifactUploader localFileUploader =
            besStreamOptions.buildEventTextFilePathConversion
                ? uploaderSupplier.get()
                : new LocalFilesArtifactUploader();
        bepTransportsBuilder.add(
            new TextFormatFileTransport(
                bepTextOutputStream,
                bepOptions,
                localFileUploader,
                artifactGroupNamer));
      } catch (IOException exception) {
        // TODO(b/125216340): Consider making this a warning instead of an error once the
        //  associated bug has been resolved.
        reportError(
            cmdLineReporter,
            cmdEnv.getBlazeModuleEnvironment(),
            "Unable to write to '"
                + besStreamOptions.buildEventTextFile
                + "'. Omitting --build_event_text_file.",
            exception,
            ExitCode.LOCAL_ENVIRONMENTAL_ERROR);
      }
    }

    if (!Strings.isNullOrEmpty(besStreamOptions.buildEventBinaryFile)) {
      try {
        BufferedOutputStream bepBinaryOutputStream =
            new BufferedOutputStream(
                Files.newOutputStream(Paths.get(besStreamOptions.buildEventBinaryFile)));

        BuildEventArtifactUploader localFileUploader =
            besStreamOptions.buildEventBinaryFilePathConversion
                ? uploaderSupplier.get()
                : new LocalFilesArtifactUploader();
        bepTransportsBuilder.add(
            new BinaryFormatFileTransport(
                bepBinaryOutputStream,
                bepOptions,
                localFileUploader,
                artifactGroupNamer));
      } catch (IOException exception) {
        // TODO(b/125216340): Consider making this a warning instead of an error once the
        //  associated bug has been resolved.
        reportError(
            cmdLineReporter,
            cmdEnv.getBlazeModuleEnvironment(),
            "Unable to write to '"
                + besStreamOptions.buildEventBinaryFile
                + "'. Omitting --build_event_binary_file.",
            exception,
            ExitCode.LOCAL_ENVIRONMENTAL_ERROR);
      }
    }

    if (!Strings.isNullOrEmpty(besStreamOptions.buildEventJsonFile)) {
      try {
        BufferedOutputStream bepJsonOutputStream =
            new BufferedOutputStream(
                Files.newOutputStream(Paths.get(besStreamOptions.buildEventJsonFile)));
        BuildEventArtifactUploader localFileUploader =
            besStreamOptions.buildEventJsonFilePathConversion
                ? uploaderSupplier.get()
                : new LocalFilesArtifactUploader();
        bepTransportsBuilder.add(
            new JsonFormatFileTransport(
                bepJsonOutputStream,
                bepOptions,
                localFileUploader,
                artifactGroupNamer));
      } catch (IOException exception) {
        // TODO(b/125216340): Consider making this a warning instead of an error once the
        //  associated bug has been resolved.
        reportError(
            cmdLineReporter,
            cmdEnv.getBlazeModuleEnvironment(),
            "Unable to write to '"
                + besStreamOptions.buildEventJsonFile
                + "'. Omitting --build_event_json_file.",
            exception,
            ExitCode.LOCAL_ENVIRONMENTAL_ERROR);
      }
    }

    BuildEventServiceTransport besTransport =
        createBesTransport(cmdEnv, uploaderSupplier, artifactGroupNamer);
    if (besTransport != null) {
      constructAndMaybeReportInvocationIdUrl();
      constructAndMaybeReportBuildRequestIdUrl();
      bepTransportsBuilder.add(besTransport);
    }

    return bepTransportsBuilder.build();
  }

  protected abstract Class<BESOptionsT> optionsClass();

  protected abstract BuildEventServiceClient getBesClient(
      BESOptionsT besOptions, AuthAndTLSOptions authAndTLSOptions)
      throws IOException, OptionsParsingException;

  protected abstract void clearBesClient();

  protected abstract Set<String> whitelistedCommands(BESOptionsT besOptions);

  protected Set<String> getBesKeywords(
      BESOptionsT besOptions, @Nullable OptionsParsingResult startupOptionsProvider) {
    return besOptions.besKeywords.stream()
        .map(keyword -> "user_keyword=" + keyword)
        .collect(ImmutableSet.toImmutableSet());
  }

  /** A prefix used when printing the invocation ID in the command line */
  protected abstract String getInvocationIdPrefix();

  /** A prefix used when printing the build request ID in the command line */
  protected abstract String getBuildRequestIdPrefix();

  // TODO(b/115961387): This method shouldn't exist. It only does because some tests are relying on
  //  the transport creation logic of this module directly.
  @VisibleForTesting
  ImmutableSet<BuildEventTransport> getBepTransports() {
    return bepTransports;
  }
}
