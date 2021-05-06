/*
 * Copyright (C) 2016 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 */

package androidx.test.espresso.remote;

import static androidx.test.espresso.Espresso.onView;
import static androidx.test.espresso.remote.InteractionResponse.RemoteError.REMOTE_ESPRESSO_ERROR_CODE;
import static androidx.test.espresso.remote.InteractionResponse.RemoteError.REMOTE_PROTOCOL_ERROR_CODE;
import static androidx.test.internal.util.LogUtil.logDebugWithProcess;
import static com.google.common.base.Preconditions.checkNotNull;

import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.os.Messenger;
import android.os.RemoteException;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.VisibleForTesting;
import android.util.Log;
import android.view.View;
import androidx.test.espresso.DataInteractionRemote;
import androidx.test.espresso.Root;
import androidx.test.espresso.ViewAction;
import androidx.test.espresso.ViewAssertion;
import androidx.test.espresso.action.RemoteViewActions;
import androidx.test.espresso.assertion.RemoteViewAssertions;
import androidx.test.espresso.matcher.RemoteHamcrestCoreMatchers13;
import androidx.test.espresso.matcher.RemoteRootMatchers;
import androidx.test.espresso.matcher.RemoteViewMatchers;
import androidx.test.espresso.remote.InteractionResponse.RemoteError;
import androidx.test.espresso.remote.InteractionResponse.Status;
import androidx.test.espresso.web.action.RemoteWebActions;
import androidx.test.espresso.web.assertion.RemoteWebViewAssertions;
import androidx.test.espresso.web.matcher.RemoteWebMatchers;
import androidx.test.espresso.web.model.RemoteWebModelAtoms;
import androidx.test.espresso.web.sugar.RemoteWebSugar;
import androidx.test.espresso.web.webdriver.RemoteWebDriverAtoms;
import androidx.test.internal.platform.tracker.UsageTrackerRegistry;
import androidx.test.internal.platform.tracker.UsageTrackerRegistry.AxtVersions;
import androidx.test.internal.runner.InstrumentationConnection;
import androidx.test.internal.util.ParcelableIBinder;
import com.google.common.base.Throwables;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import org.hamcrest.Matcher;

/**
 * A singleton class that facilitates communication between other Espresso instance that may be
 * running in different processes.
 *
 * <p>This class depends on {@link InstrumentationConnection} to notify about the discovery of other
 * remote Espresso instances and provide their {@link Messenger} object to use for further IPC.
 *
 * <p>To get the instance of this object {@link #getInstance()} should be called. The user of this
 * class should then call {@link #init()} prior to attempting to use any functionality of this
 * class. Call {@link #terminate()} after using EspressoRemote to release any resources. Failure to
 * do so will lead to memory leaks and unexpected behavior.
 */
public final class EspressoRemote implements RemoteInteraction {
  private static final String TAG = "EspressoRemote";

  static {
    UsageTrackerRegistry.getInstance().trackUsage("Espresso-MPE", AxtVersions.ESPRESSO_VERSION);
  }

  private static final EspressoRemote DEFAULT_INSTANCE = new EspressoRemote();

  /** Fully qualified class name to serve as the unique identifier for Espresso */
  @VisibleForTesting static final String TYPE = EspressoRemote.class.getCanonicalName();

  @VisibleForTesting static final String BUNDLE_KEY_TYPE = "type";
  @VisibleForTesting static final String BUNDLE_KEY_UUID = "uuid";
  @VisibleForTesting static final String BUNDLE_KEY_PROTO = "proto";

  @VisibleForTesting static InstrumentationConnection instrumentationConnection;

  private static final int MSG_TERMINATE = 1;
  private static final int MSG_FORWARD_TO_REMOTE_ESPRESSO = 2;
  @VisibleForTesting static final int MSG_HANDLE_ESPRESSO_REQUEST = 3;
  @VisibleForTesting static final int MSG_HANDLE_ESPRESSO_RESPONSE = 4;
  @VisibleForTesting static final int MSG_HANDLE_EMPTY_REQUEST = 5;

  /** Represents whether the current instance is running in a remote process or not */
  private static volatile boolean isRemoteProcess;

  /** {@link IncomingHandler} that will handler incoming messages */
  @VisibleForTesting IncomingHandler incomingHandler;

  /** package private constructor to aid with testing */
  @VisibleForTesting
  EspressoRemote(InstrumentationConnection instrumentationConnection) {
    EspressoRemote.instrumentationConnection = instrumentationConnection;
  }

  private EspressoRemote() {
    this(InstrumentationConnection.getInstance());
  }

  /** Returns an instance of {@link EspressoRemote} object. */
  public static EspressoRemote getInstance() {
    return DEFAULT_INSTANCE;
  }

  /**
   * A method that will be passed in as an Instrumentation runnerArg which will than be reflectively
   * called by the runner to init this class.
   *
   * <p>See "remoteMethod" runner argument for more information.
   */
  @SuppressWarnings("unused") // Used Reflectively
  private static void remoteInit() {
    logDebugWithProcess(TAG, "remoteInit called");
    getInstance().init();
  }

  /**
   * Must be called prior to using any functionality of this class.
   *
   * <p>During initialization the instance of this class will be registered with {@link
   * InstrumentationConnection}.
   */
  public synchronized void init() {
    logDebugWithProcess(TAG, "init called");

    if (null == incomingHandler) {
      Log.i(TAG, "Initializing Espresso Remote of type: " + TYPE);
      RemoteInteractionRegistry.registerInstance(DEFAULT_INSTANCE);
      initRemoteRegistry();
      HandlerThread handlerThread = new HandlerThread("EspressoRemoteThread");
      handlerThread.start();
      incomingHandler = new IncomingHandler(handlerThread.getLooper());
      instrumentationConnection.registerClient(TYPE, incomingHandler.messengerHandler);
    }
  }

  /**
   * Must be called to disable further use of this class.
   *
   * <p>During termination the instance of this class will be un-registered with {@link
   * InstrumentationConnection} and clear the list of known remote Espresso Messengers.
   */
  public synchronized void terminate() {
    logDebugWithProcess(TAG, "terminate called");
    if (incomingHandler != null) {
      incomingHandler.getEspressoMessage(MSG_TERMINATE).sendToTarget();
      incomingHandler = null;
    }
  }

  @Override
  public synchronized boolean isRemoteProcess() {
    return isRemoteProcess;
  }

  @Override
  public synchronized Callable<Void> createRemoteCheckCallable(
      final Matcher<Root> rootMatcher,
      final Matcher<View> viewMatcher,
      final Map<String, IBinder> iBinders,
      final ViewAssertion viewAssertion) {

    return createRemoteInteraction(
        new Runnable() {
          @Override
          public void run() {
            Log.i(
                TAG,
                String.format(
                    Locale.ROOT,
                    "Attempting to run check interaction on a remote process "
                        + "for ViewAssertion: %s",
                    viewAssertion));
            InteractionRequest interactionRequest =
                new InteractionRequest.Builder()
                    .setRootMatcher(rootMatcher)
                    .setViewMatcher(viewMatcher)
                    .setViewAssertion(viewAssertion)
                    .build();

            // Send remote interaction request to other Espresso instances
            initiateRemoteCall(interactionRequest.toProto().toByteArray(), iBinders);
          }
        });
  }

  @Override
  public synchronized Callable<Void> createRemotePerformCallable(
      final Matcher<Root> rootMatcher,
      final Matcher<View> viewMatcher,
      final Map<String, IBinder> iBinders,
      final ViewAction... viewActions) {
    return createRemoteInteraction(
        new Runnable() {
          @Override
          public void run() {
            for (ViewAction viewAction : viewActions) {
              Log.i(
                  TAG,
                  String.format(
                      Locale.ROOT,
                      "Attempting to run perform interaction on a remote "
                          + "processes for ViewAction: %s",
                      viewAction));
              // TODO(b/32948667): This will create a request for every action.
              InteractionRequest interactionRequest =
                  new InteractionRequest.Builder()
                      .setRootMatcher(rootMatcher)
                      .setViewMatcher(viewMatcher)
                      .setViewAction(viewAction)
                      .build();

              // Send remote interaction request to other Espresso instances
              initiateRemoteCall(interactionRequest.toProto().toByteArray(), iBinders);
            }
          }
        });
  }

  private Callable<Void> createRemoteInteraction(final Runnable runnable) {
    return new Callable<Void>() {
      @Override
      public Void call() throws InterruptedException {
        long[] waitTimes = {
          10, 50, 100, 500, TimeUnit.SECONDS.toMillis(2), TimeUnit.SECONDS.toMillis(30)
        };

        for (long waitTime : waitTimes) {
          Log.i(TAG, "No remote Espresso instance - waiting: " + waitTime + "ms for one to start");
          Thread.sleep(waitTime);

          if (hasRemoteEspressoInstances()) {
            runnable.run();
            return null;
          }
        }
        throw new NoRemoteEspressoInstanceException("No remote Espresso instances at this time.");
      }
    };
  }

  /**
   * Initiate a remote Espresso call to all known remote Espresso instances (if any).
   *
   * @param data a byte representation of {@link InteractionRequest} proto.
   * @param iBinders a map of {@link IBinder IBinders} that need to be passed along to the remote
   *     process
   */
  @VisibleForTesting
  void initiateRemoteCall(byte[] data, Map<String, IBinder> iBinders) {
    logDebugWithProcess(TAG, "initiateRemoteCall");
    try {
      ResponseHolder responseHolder =
          sendMessageSynchronously(MSG_HANDLE_ESPRESSO_REQUEST, data, iBinders);
      reportResults(responseHolder);
    } catch (InterruptedException ignore) {
      // ignore, already logged a warning
    }
  }

  private void sendEmptyRequest() {
    logDebugWithProcess(TAG, "sendEmptyRequest");
    try {
      sendMessageSynchronously(MSG_HANDLE_EMPTY_REQUEST, null, null);
      // no response to handle
    } catch (InterruptedException ignore) {
      // ignore, already logged a warning
    }
  }

  private synchronized ResponseHolder sendMessageSynchronously(
      int what, @Nullable byte[] data, Map<String, IBinder> iBinders) throws InterruptedException {
    UUID uuid = UUID.randomUUID();

    logDebugWithProcess(
        TAG, String.format(Locale.ROOT, "Sending sync msg [%s] with uuid [%s]", what, uuid));

    CountDownLatch latch = new CountDownLatch(1);
    ResponseHolder responseHolder = new ResponseHolder(latch);

    Message msg = incomingHandler.getEspressoMessage(MSG_FORWARD_TO_REMOTE_ESPRESSO);
    msg.arg1 = what;
    Bundle bundle = msg.getData();
    bundle.putSerializable(BUNDLE_KEY_UUID, uuid);
    if (data != null) {
      bundle.putByteArray(BUNDLE_KEY_PROTO, data);
    }

    // Add any iBinders to the bundle that need to be send to the other side
    setIBindersToBundle(iBinders, bundle);

    msg.setData(bundle);

    incomingHandler.associateResponse(uuid, responseHolder);
    incomingHandler.sendMessage(msg);
    try {
      latch.await();
      return responseHolder;
    } catch (InterruptedException ie) {
      Log.w(
          TAG,
          String.format(
              Locale.ROOT,
              "Interrupted while waiting for a response from msg [%s] with uuid [%s]",
              what,
              uuid),
          ie);
      // Send over an empty request to remote Espresso instance and wait for it to return. This
      // insures that all prior messages were served by the remote process before we send over a
      // new message. Helps with stability.
      sendEmptyRequest();
      Thread.currentThread().interrupt();
      throw ie;
    } finally {
      incomingHandler.disassociateResponse(uuid);
    }
  }

  private static void setIBindersToBundle(Map<String, IBinder> iBinders, Bundle bundle) {
    if (iBinders != null && !iBinders.isEmpty()) {
      Iterator<Map.Entry<String, IBinder>> iterator = iBinders.entrySet().iterator();
      while (iterator.hasNext()) {
        Map.Entry<String, IBinder> binderEntry = iterator.next();
        bundle.putParcelable(binderEntry.getKey(), new ParcelableIBinder(binderEntry.getValue()));
      }
    }
  }

  private synchronized boolean hasRemoteEspressoInstances() {
    Set<Messenger> clientsForType = instrumentationConnection.getClientsForType(TYPE);
    // This instance should be ignored from the check
    return clientsForType.size() > 1;
  }

  private static void initRemoteRegistry() {
    RemoteDescriptorRegistry remoteDescriptorRegistry = RemoteDescriptorRegistry.getInstance();
    RemoteRootMatchers.init(remoteDescriptorRegistry);
    RemoteViewMatchers.init(remoteDescriptorRegistry);
    RemoteViewActions.init(remoteDescriptorRegistry);
    RemoteViewAssertions.init(remoteDescriptorRegistry);
    RemoteHamcrestCoreMatchers13.init(remoteDescriptorRegistry);

    // Espresso Remote internal matchers
    DataInteractionRemote.init(remoteDescriptorRegistry);

    // Espresso Web
    RemoteWebActions.init(remoteDescriptorRegistry);
    RemoteWebModelAtoms.init(remoteDescriptorRegistry);
    RemoteWebSugar.init(remoteDescriptorRegistry);
    RemoteWebDriverAtoms.init(remoteDescriptorRegistry);
    RemoteWebViewAssertions.init(remoteDescriptorRegistry);
    RemoteWebMatchers.init(remoteDescriptorRegistry);
  }

  private static void reportResults(ResponseHolder responseHolder) {
    byte[] protoByteArray = responseHolder.getData().getByteArray(BUNDLE_KEY_PROTO);
    if (null == protoByteArray) {
      throw new IllegalStateException("Espresso remote response doesn't contain a valid response");
    }

    try {
      InteractionResponse interactionResponse =
          new InteractionResponse.Builder().setResultProto(protoByteArray).build();

      if (Status.Error == interactionResponse.getStatus()) {
        if (!interactionResponse.hasRemoteError()) {
          throw new IllegalStateException(
              "Interaction response reported Status.Error, but no"
                  + "error message was attached to interaction response: "
                  + interactionResponse);
        }
        throw new RemoteEspressoException(interactionResponse.getRemoteError().getDescription());
      }

    } catch (RemoteProtocolException re) {
      Log.e(TAG, "Could not parse Interaction response", re);
      throw new RemoteEspressoException("Could not parse Interaction response", re);
    }
  }

  private static class ResponseHolder {
    private final CountDownLatch latch;
    private Bundle data = null;

    public ResponseHolder(CountDownLatch latch) {
      this.latch = latch;
    }

    public void setData(Bundle data) {
      this.data = data;
    }

    public Bundle getData() {
      return data;
    }

    public CountDownLatch getLatch() {
      return latch;
    }
  }

  class IncomingHandler extends Handler {

    /**
     * Map containing latch {@link UUID}s to aid with message synchronization Note: This map should
     * be only modified {@link #associateResponse(UUID, ResponseHolder)} or {@link
     * #disassociateResponse(UUID)}
     */
    private final Map<UUID, ResponseHolder> responses = new HashMap<>();

    /** Target we publish for clients to send messages to IncomingHandler. */
    Messenger messengerHandler = new Messenger(this);

    public IncomingHandler(Looper looper) {
      super(looper);
      if (Looper.getMainLooper() == looper || Looper.myLooper() == looper) {
        throw new IllegalStateException(
            "This handler should not be using the main thread looper "
                + "nor the instrumentation thread looper.");
      }
    }

    @Override
    public void handleMessage(Message msg) {
      // When using Messenger to send messages across processes with a custom Parcelable, the
      // receiving end ClassLoader is only aware of parcelables that are part of the Android
      // framework. Thus, we need to use ours to account for the custom Parcelable class.
      msg.getData().setClassLoader(getClass().getClassLoader());

      if (!TYPE.equals(msg.getData().getString(BUNDLE_KEY_TYPE)) || null == msg.replyTo) {
        Log.w(TAG, "Type mismatch or no valid Messenger present, ignoring message: " + msg);
        return;
      }

      switch (msg.what) {
        case MSG_TERMINATE:
          logDebugWithProcess(TAG, "handleMessage: MSG_TERMINATE");
          doDie();
          break;
        case MSG_FORWARD_TO_REMOTE_ESPRESSO:
          logDebugWithProcess(TAG, "handleMessage: MSG_FORWARD_TO_REMOTE_ESPRESSO");
          sendMsgToRemoteEspressos(msg.arg1, msg.getData());
          break;
        case MSG_HANDLE_ESPRESSO_REQUEST:
          logDebugWithProcess(TAG, "handleMessage: MSG_HANDLE_ESPRESSO_REQUEST");
          handleEspressoRequest(msg.replyTo, msg.getData());
          break;
        case MSG_HANDLE_ESPRESSO_RESPONSE:
          logDebugWithProcess(TAG, "handleMessage: MSG_HANDLE_ESPRESSO_RESPONSE");
          handleEspressoResponse(msg.getData());
          break;
        case MSG_HANDLE_EMPTY_REQUEST:
          logDebugWithProcess(TAG, "handleMessage: MSG_HANDLE_EMPTY_REQUEST");
          // Nothing to do just send a response back.
          sendMsgToRemoteEspressos(MSG_HANDLE_ESPRESSO_RESPONSE, msg.getData());
          break;
        default:
          Log.w(TAG, "Unknown message code received: " + msg.what);
          super.handleMessage(msg);
      }
    }

    private void associateResponse(final UUID latchId, final ResponseHolder response) {
      FutureTask<Void> associationTask =
          new FutureTask<>(
              new Callable<Void>() {
                @Override
                public Void call() {
                  responses.put(latchId, response);
                  return null;
                }
              });
      post(associationTask);

      try {
        associationTask.get();
      } catch (InterruptedException e) {
        // Shouldn't happen, always waiting for finish
        throw new IllegalStateException(e);
      } catch (ExecutionException e) {
        // Shouldn't happen, just adding (key,value) to a map
        throw new IllegalStateException(e.getCause());
      }
    }

    private void disassociateResponse(final UUID latchId) {
      FutureTask<Void> disassociationTask =
          new FutureTask<>(
              new Callable<Void>() {
                @Override
                public Void call() {
                  responses.remove(latchId);
                  return null;
                }
              });
      post(disassociationTask);

      try {
        disassociationTask.get();
      } catch (InterruptedException e) {
        // Shouldn't happen, always waiting for finish
        throw new IllegalStateException(e);
      } catch (ExecutionException e) {
        // Shouldn't happen, just adding (key,value) to a map
        throw new IllegalStateException(e.getCause());
      }
    }

    private void doDie() {
      instrumentationConnection.unregisterClient(TYPE, messengerHandler);
      getLooper().quit();
    }

    /**
     * Helper method to construct an Espresso defined {@link Message}.
     *
     * <p>The Espresso message will include:
     *
     * <ul>
     *   <li>{@link Message#what} The message code, passed as a param
     *   <li>{@link Message#replyTo} The Espresso {@link #messengerHandler}
     *   <li>{@link Message#getData()} will contain {@link #TYPE} under {@link #BUNDLE_KEY_TYPE}
     * </ul>
     *
     * @param what User-defined message code so that the recipient can identify what this message is
     *     about.
     * @return the Espresso Message
     */
    private Message getEspressoMessage(int what) {
      Message msg = incomingHandler.obtainMessage(what);
      msg.replyTo = messengerHandler;
      Bundle bundle = new Bundle();
      bundle.putString(BUNDLE_KEY_TYPE, TYPE);
      msg.setData(bundle);
      return msg;
    }

    /**
     * Send request to remote Espresso instances (if any).
     *
     * @param what User-defined message code so that the recipient can identify what this message is
     *     about.
     * @param data A Bundle of arbitrary data associated with this message
     */
    private void sendMsgToRemoteEspressos(int what, Bundle data) {
      logDebugWithProcess(TAG, "sendMsgToRemoteEspressos called");

      Message msg = getEspressoMessage(what);
      msg.setData(data);

      Set<Messenger> remoteClients = instrumentationConnection.getClientsForType(TYPE);
      for (Messenger remoteEspresso : remoteClients) {
        if (messengerHandler.equals(remoteEspresso)) {
          // avoid sending message to self
          continue;
        }
        try {
          remoteEspresso.send(msg);
        } catch (RemoteException e) {
          // In this case the remote process was terminated or crashed before we could
          // even do anything with it; there is nothing we can do other than unregister the
          // Espresso instance.
          Log.w(TAG, "The remote process is terminated unexpectedly", e);
          instrumentationConnection.unregisterClient(TYPE, remoteEspresso);
        }
      }
    }

    /**
     * Deconstructs the given interaction proto and attempts to run it in the current process.
     *
     * <p>1. deconstruct InteractionRequestProto into an interaction Espresso can understand 2.
     * attempt to run the desired interaction 3. send a response to the caller whether there is
     * nothing to execute on (1 is false) or the interaction failed (e.g due to an assertion)
     *
     * @param caller The caller that initiated this request
     * @param data A Bundle including InteractionRequestProto repressing the Espresso interaction
     */
    private void handleEspressoRequest(Messenger caller, Bundle data) {
      UUID uuid = (UUID) data.getSerializable(BUNDLE_KEY_UUID);
      logDebugWithProcess(
          TAG, String.format(Locale.ROOT, "handleEspressoRequest for id: %s", uuid));

      Message msg = getEspressoMessage(MSG_HANDLE_ESPRESSO_RESPONSE);
      Bundle resultData = msg.getData();
      // copy over the request UUID
      resultData.putSerializable(BUNDLE_KEY_UUID, uuid);
      // attempt to execute the request and save the result
      isRemoteProcess = true;
      InteractionResponse interactionResponse = executeRequest(data);
      resultData.putByteArray(BUNDLE_KEY_PROTO, interactionResponse.toProto().toByteArray());
      msg.setData(resultData);

      try {
        caller.send(msg);
      } catch (RemoteException e) {
        // In this case the remote process was terminated or crashed before we could
        // even do anything with it; there is nothing we can do other than unregister the
        // Espresso caller instance.
        Log.w(TAG, "The remote caller process is terminated unexpectedly", e);
        instrumentationConnection.unregisterClient(TYPE, caller);
      }
    }

    private InteractionResponse executeRequest(Bundle data) {
      byte[] protoByteArray = data.getByteArray(BUNDLE_KEY_PROTO);
      Status status = Status.Error;
      RemoteError remoteError = null;

      try {
        // Parse Interaction Request
        InteractionRequest interactionRequest =
            new InteractionRequest.Builder().setRequestProto(protoByteArray).build();

        // Check if this interaction was already executed elsewhere
        ParcelableIBinder executionStatusIBinder =
            data.getParcelable(RemoteInteraction.BUNDLE_EXECUTION_STATUS);
        boolean canExecute = false;
        if (executionStatusIBinder != null) {
          IInteractionExecutionStatus executionStatus =
              IInteractionExecutionStatus.Stub.asInterface(executionStatusIBinder.getIBinder());
          try {
            canExecute = executionStatus.canExecute();
          } catch (RemoteException e) {
            throw new RuntimeException(
                "Unable to query interaction execution status", e.getCause());
          }
        }

        if (canExecute) {
          // Execute Espresso code to un-serialize and run view matchers, actions and assertions.
          status = RemoteInteractionStrategy.from(interactionRequest, data).execute();
        }

      } catch (RemoteProtocolException rpe) {
        remoteError =
            new RemoteError(REMOTE_PROTOCOL_ERROR_CODE, Throwables.getStackTraceAsString(rpe));
      } catch (RuntimeException re) {
        remoteError =
            new RemoteError(REMOTE_ESPRESSO_ERROR_CODE, Throwables.getStackTraceAsString(re));
      } catch (Error error) {
        remoteError =
            new RemoteError(REMOTE_ESPRESSO_ERROR_CODE, Throwables.getStackTraceAsString(error));
      }

      return new InteractionResponse.Builder()
          .setStatus(status)
          .setRemoteError(remoteError)
          .build();
    }

    private void handleEspressoResponse(Bundle data) {
      UUID uuid = (UUID) data.getSerializable(BUNDLE_KEY_UUID);
      logDebugWithProcess(TAG, "handleEspressoResponse for id: %s", uuid);
      ResponseHolder response = responses.get(uuid);
      if (null == response) {
        // TODO(b/32968974) Decide whether logging is sufficient
        throw new IllegalStateException("Received a response from an unknown message: " + uuid);
      }

      // set the response to be handled on the instrumentation thread
      response.setData(data);
      // notify
      response.getLatch().countDown();
    }
  } // close IncomingHandler

  @VisibleForTesting
  abstract static class RemoteInteractionStrategy {
    public static RemoteInteractionStrategy from(
        @NonNull InteractionRequest interactionRequest, Bundle bundle) {
      checkNotNull(interactionRequest, "interactionRequest cannot be null!");
      logDebugWithProcess(
          TAG,
          "Creating RemoteInteractionStrategy from values:\n"
              + "RootMatcher: %s\n"
              + "ViewMatcher: %s\n"
              + "ViewAction: %s\n"
              + "View Assertion: %s",
          interactionRequest.getRootMatcher(),
          interactionRequest.getViewMatcher(),
          interactionRequest.getViewAction(),
          interactionRequest.getViewAssertion());

      // If a view action is set on the interaction request, perform an action
      if (interactionRequest.getViewAction() != null) {
        // Additionally check if the action is Bindable and set the IBinder
        ViewAction viewAction = interactionRequest.getViewAction();
        setIBinderFromBundle(viewAction, bundle);

        return new OnViewPerformStrategy(
            interactionRequest.getRootMatcher(), interactionRequest.getViewMatcher(), viewAction);
      } else {
        // Additionally check if the assertion is Bindable and set the IBinder
        ViewAssertion viewAssertion = interactionRequest.getViewAssertion();
        setIBinderFromBundle(viewAssertion, bundle);
        // Otherwise a check a view assertion
        return new OnViewCheckStrategy(
            interactionRequest.getRootMatcher(),
            interactionRequest.getViewMatcher(),
            viewAssertion);
      }
    }

    private static void setIBinderFromBundle(Object object, Bundle bundle) {
      if (object instanceof Bindable) {
        setIBinderFromBundle((Bindable) object, bundle);
      }
    }

    private static void setIBinderFromBundle(Bindable bindable, Bundle bundle) {
      ParcelableIBinder parcelableIBinder = bundle.getParcelable(bindable.getId());
      bindable.setIBinder(parcelableIBinder.getIBinder());
    }

    abstract Status execute();
  }

  private static class OnViewPerformStrategy extends RemoteInteractionStrategy {

    private final Matcher<Root> rootMatcher;
    private final Matcher<View> viewMatcher;
    private final ViewAction viewAction;

    public OnViewPerformStrategy(
        Matcher<Root> rootMatcher, Matcher<View> viewMatcher, ViewAction viewAction) {
      this.rootMatcher = rootMatcher;
      this.viewMatcher = viewMatcher;
      this.viewAction = viewAction;
    }

    @Override
    public Status execute() {
      logDebugWithProcess(
          TAG,
          "Remotely executing:\nonView(%s).inRoot(%s).perform(%s)",
          rootMatcher,
          viewMatcher,
          viewAction);
      onView(viewMatcher).inRoot(rootMatcher).perform(viewAction);
      return Status.Ok;
    }
  }

  private static class OnViewCheckStrategy extends RemoteInteractionStrategy {

    private final Matcher<Root> rootMatcher;
    private final Matcher<View> viewMatcher;
    private final ViewAssertion viewAssertion;

    public OnViewCheckStrategy(
        Matcher<Root> rootMatcher, Matcher<View> viewMatcher, ViewAssertion viewAssertion) {
      this.rootMatcher = rootMatcher;
      this.viewMatcher = viewMatcher;
      this.viewAssertion = viewAssertion;
    }

    @Override
    public Status execute() {
      logDebugWithProcess(
          TAG,
          "Remotely executing:\nonView(%S).inRoot(%s).check(%s)",
          rootMatcher,
          viewMatcher,
          viewAssertion);
      onView(viewMatcher).inRoot(rootMatcher).check(viewAssertion);
      return Status.Ok;
    }
  }
}
