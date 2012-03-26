package org.iobit.servlet3.endpoint;

import flex.messaging.FlexContext;
import flex.messaging.FlexSession;
import flex.messaging.MessageException;
import flex.messaging.client.EndpointPushNotifier;
import flex.messaging.client.FlexClient;
import flex.messaging.client.UserAgentSettings;
import flex.messaging.config.ConfigMap;
import flex.messaging.endpoints.BaseStreamingHTTPEndpoint;
import flex.messaging.log.Log;
import flex.messaging.messages.AcknowledgeMessage;
import flex.messaging.messages.AsyncMessage;
import flex.messaging.util.TimeoutManager;
import flex.messaging.util.UserAgentManager;

import javax.servlet.AsyncContext;
import javax.servlet.AsyncEvent;
import javax.servlet.AsyncListener;
import javax.servlet.ServletOutputStream;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;

/**
 * Base for HTTP-based endpoints that support streaming HTTP connections to
 * connected clients.
 * Each streaming connection managed by this endpoint consumes one of the request
 * handler threads provided by the servlet container, so it is not highly scalable
 * but offers performance advantages over client polling for clients receiving
 * a steady, rapid stream of pushed messages.
 * This endpoint does not support polling clients and will fault any poll requests
 * that are received. To support polling clients use subclasses of
 * BaseHTTPEndpoint instead.
 */
public abstract class BaseServlet3Endpoint extends BaseStreamingHTTPEndpoint {
    private static final byte NULL_BYTE = (byte)0; // signal that a chunk of data should be skipped by the client.
    private static final int DEFAULT_MAX_STREAMING_CLIENTS = 10;

    // Using a single threaded executor for handling all the pushing
    private ExecutorService notifierThread = null;
    private Runnable notifierRunnable = null;
    private static final Queue<AsyncContext> queue = new ConcurrentLinkedQueue<AsyncContext>();
    private static boolean notifierRunning = false;

    /**
     * Used to synchronize sets and gets to the number of streaming clients.
     */
    protected final Object lock = new Object();

    /**
     * This flag is volatile to allow for consistent reads across thread without
     * needing to pay the cost for a synchronized lock for each read.
     */
    private volatile boolean canStream = true;

    /**
     * Manages timing out EndpointPushNotifier instances.
     */
    private volatile TimeoutManager pushNotifierTimeoutManager;

    /**
     * A Map(EndpointPushNotifier, Boolean.TRUE) containing all currently open streaming notifiers
     * for this endpoint. Used for clean shutdown.
     */
    private ConcurrentHashMap<String, EndpointPushNotifier> currentStreamingRequests;

    /**
     * The maximum number of clients that will be allowed to establish
     * a streaming HTTP connection with the endpoint
     */
    private int maxStreamingClients = DEFAULT_MAX_STREAMING_CLIENTS;

    /**
     * Returns the maximum number of clients that will be allowed to establish
     * a streaming HTTP connection with the endpoint.
     *
     * @return The maximum number of clients that will be allowed to establish
     * a streaming HTTP connection with the endpoint.
     */
    public int getMaxStreamingClients() {
        return maxStreamingClients;
    }

    /**
     * Sets the maximum number of clients that will be allowed to establish
     * a streaming HTTP connection with the server.
     *
     * @param maxStreamingClients The maximum number of clients that will be allowed
     * to establish a streaming HTTP connection with the server.
     */
    public void setMaxStreamingClients(int maxStreamingClients) {
        this.maxStreamingClients = maxStreamingClients;
        canStream = (streamingClientsCount < maxStreamingClients);
    }
    
    /**
     * Constructs an unmanaged <code>BaseStreamingHTTPEndpoint</code>.
     */
    public BaseServlet3Endpoint() {
        this(false);
    }

    /**
     * Constructs an <code>BaseStreamingHTTPEndpoint</code> with the indicated management.
     *
     * @param enableManagement <code>true</code> if the <code>BaseStreamingHTTPEndpoint</code>
     * is manageable; otherwise <code>false</code>.
     */
    public BaseServlet3Endpoint(boolean enableManagement) {
        super(enableManagement);
    }

    /**
     * Initializes the <code>Endpoint</code> with the properties.
     * If subclasses override, they must call <code>super.initialize()</code>.
     *
     * @param id Id of the <code>Endpoint</code>.
     * @param properties Properties for the <code>Endpoint</code>.
     */
    @Override
    public void initialize(String id, ConfigMap properties) {
        super.initialize(id, properties);
        debug("Initializing BaseServelt3Endpoint");

        // Maximum number of clients allowed to have streaming HTTP connections with the endpoint.
        maxStreamingClients = properties.getPropertyAsInt("max-streaming-clients", DEFAULT_MAX_STREAMING_CLIENTS);
        debug("Max client to stream: " + maxStreamingClients);
        
        // Set initial state for the canWait flag based on whether we allow waits or not.
        canStream = (maxStreamingClients > 0);
        final FlexSession session = FlexContext.getFlexSession();
        notifierRunnable = new Runnable() {
            public void run() {
            	notifierRunning = true;
            	debug ( "notifyThread Started queue : " + queue.size() );
            	
            	//Continue to push only if the queue contains AsyncContexts - HT
                while ( !queue.isEmpty() ) {
                	
                    for (AsyncContext ac : queue) {
                    	
                        EndpointPushNotifier notifier = null;
                        try {
                        	//If the AsyncContext has completed (due to timeout) then remove it.
                            if (ac.getRequest() == null || !ac.getRequest().isAsyncStarted() ) {
                                queue.remove(ac);
                                cleanUp(notifier, session);
                                continue;
                            }
                            
                            notifier =
                                (EndpointPushNotifier) ac.getRequest().getAttribute("pushNotifier");
                            FlexClient flexClient = 
                                (FlexClient) ac.getRequest().getAttribute("flexClient");
                            HttpServletResponse res = (HttpServletResponse) ac.getResponse();
                            ServletOutputStream os = res.getOutputStream();
                            if (notifier.isClosed()) {
                                debug("notifier is closed, time to end our streaming");
                                // Terminate the response.
                                streamChunk(null, os, res);
                                if (ac.getRequest().isAsyncStarted()) {
                                    ac.complete();
                                }
                                queue.remove(ac);
                                cleanUp(notifier, session);
                            }
                            synchronized (notifier.pushNeeded) {
                                // Drain any messages that might have been accumulated
                                // while the previous drain was being processed.
                                streamMessages(notifier.drainMessages(), os, res);

                                notifier.pushNeeded.wait(getServerToClientHeartbeatMillis());

                                List<AsyncMessage> messages = notifier.drainMessages();
                                // If there are no messages to send to the client, send an null
                                // byte as a heartbeat to make sure the client is still valid.
                                if (messages == null && getServerToClientHeartbeatMillis() > 0) {
                                    try {
                                        //debug("stream hearbeat");
                                        os.write(NULL_BYTE);
                                        res.flushBuffer();
                                    } catch (Exception e) {
                                        if (Log.isWarn())
                                            log.warn("Endpoint with id '" + getId() + "' is closing the streaming connection to FlexClient with id '"
                                                    + flexClient.getId() + "' because endpoint encountered a socket write error" +
                                            ", possibly due to an unresponsive FlexClient.", e);
                                        continue; // Exit the wait loop.
                                    }
                                } else { // Otherwise stream the messages to the client.
                                    debug("stream messages");
                                    // Update the last time notifier was used to drain messages.
                                    // Important for idle timeout detection.
                                    streamMessages(messages, os, res);
                                    notifier.updateLastUse();
                                }
                                
                            }
                            // Update the FlexClient last use time to prevent FlexClient from
                            // timing out when the client is still subscribed. It is important
                            // to do this outside synchronized(notifier.pushNeeded) to avoid
                            // thread deadlock!
                            flexClient.updateLastUse();
                        } catch (Exception ex) {
                            ex.printStackTrace();
                            if (ac.getRequest().isAsyncStarted()) {
                                ac.complete();
                            }
                            queue.remove(ac);
                            cleanUp(notifier, session);
                        }
                    }
                }
                
                debug ( "notifyThread Ended queue : " + queue.size() );
                notifierRunning = false;
                
            }

            private void cleanUp(EndpointPushNotifier notifier, FlexSession session) {
                try {
 					debug("cleanUp");
//                	This is already done in onComplete - HT                	
//                    synchronized (lock) {
//                        --streamingClientsCount; 
//                        canStream = (streamingClientsCount < maxStreamingClients);
//                        
//                        //If the flex client closes the session the FlexSession would be null at this point -HT
//                        if (session != null) { 
//	                        synchronized (session) {
//	                            --session.streamingConnectionsCount;
//	                            session.canStream = (session.streamingConnectionsCount < session.maxConnectionsPerSession);
//	                        }
//                        }
//                    }
//    
                    if (notifier != null && currentStreamingRequests != null) {
                        currentStreamingRequests.remove(notifier.getNotifierId());
                        notifier.close();
                    }
                    
                    
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }
                
        };
        
        //Create a single threaded executor - HT
        notifierThread = Executors.newSingleThreadExecutor();
    }


    private void debug(String msg) {
        System.out.println(Thread.currentThread().getName() + " - " + msg);
    }

    @Override
    public void start() {
        debug("Goind to start servelt 3 endpoint");
        if (isStarted())
            return;

        super.start();

        if (getConnectionIdleTimeoutMinutes() > 0) {
            pushNotifierTimeoutManager = new TimeoutManager(
                    new ThreadFactory() {
                        int counter = 1;
                        public synchronized Thread newThread(Runnable runnable) {
                            Thread t = new Thread(runnable);
                            t.setName(getId() + "-StreamingConnectionTimeoutThread-" + counter++);
                            return t;
                        }
            });
        }

        currentStreamingRequests = new ConcurrentHashMap<String, EndpointPushNotifier>();
    }

    /**
     * @see flex.messaging.endpoints.AbstractEndpoint#stop()
     */
    @Override
    public void stop() {
        debug("stopping!!!");
        if (!isStarted())
            return;

        // Shutdown the timeout manager for streaming connections cleanly.
        if (pushNotifierTimeoutManager != null) {
            pushNotifierTimeoutManager.shutdown();
            pushNotifierTimeoutManager = null;
        }

        // Shutdown any currently open streaming connections.
        for (EndpointPushNotifier notifier : currentStreamingRequests.values())
            notifier.close();

        currentStreamingRequests = null;
        
        clearThreadLocals();
        
        debug("before notifier shutdown");
        //Shutdown the single threaded executor - HT
        notifierThread.shutdownNow();
        //notifierThread.shutdown();
        
        debug("before super.stop");
        super.stop();
        
        debug("stop completing");
    }

    //--------------------------------------------------------------------------
    //
    // Protected Methods
    //
    //--------------------------------------------------------------------------

    /**
     * Handles streaming connection open command sent by the FlexClient.
     *
     * @param req The <code>HttpServletRequest</code> to service.
     * @param res The <code>HttpServletResponse</code> to be used in case an error
     * has to be sent back.
     * @param flexClient FlexClient that requested the streaming connection.
     */
    protected void handleFlexClientStreamingOpenRequest(HttpServletRequest req, HttpServletResponse res, FlexClient flexClient) {
        debug("we are getting open request");
        final FlexSession session = FlexContext.getFlexSession();
        if (canStream && session.canStream) {
            debug("can stream");
            debug("streamingClientsCount " + streamingClientsCount);
            debug("maxConnectionsPerSession " + session.maxConnectionsPerSession);
            // If canStream/session.canStream is true it means we currently have
            // less than the max number of allowed streaming threads, per endpoint/session.

            // We need to protect writes/reads to the stream count with the endpoint's lock.
            // Also, we have to be careful to handle the case where two threads get to this point when only
            // one streaming spot remains; one thread will win and the other needs to fault.
            boolean thisThreadCanStream;
            synchronized (lock) {
                ++streamingClientsCount;
                if (streamingClientsCount == maxStreamingClients) {
                    thisThreadCanStream = true; // This thread got the last spot.
                    canStream = false;
                } else if (streamingClientsCount > maxStreamingClients) {
                    thisThreadCanStream = false; // This thread was beaten out for the last spot.
                    --streamingClientsCount; // Decrement the count because we're not going to grant the streaming right to the client.
                } else {
                    // We haven't hit the limit yet, allow this thread to stream.
                    thisThreadCanStream = true;
                }
            }

            // If the thread cannot wait due to endpoint streaming connection
            // limit, inform the client and return.
            if (!thisThreadCanStream) {
                if (Log.isError())
                    log.error("Endpoint with id '" + getId() + "' cannot grant streaming connection to FlexClient with id '"
                            + flexClient.getId() + "' because max-streaming-clients limit of '"
                            + maxStreamingClients + "' has been reached.");
                try {
                    // Return an HTTP status code 400.
                    res.sendError(HttpServletResponse.SC_BAD_REQUEST);
                } catch (IOException ignore) {}
                return;
            }

            // Setup for specific user agents.
            byte[] kickStartBytesToStream = null;
            String userAgentValue = req.getHeader(UserAgentManager.USER_AGENT_HEADER_NAME);
            UserAgentSettings agentSettings = userAgentManager.match(userAgentValue);
            if (agentSettings != null) {
                synchronized (session) {
                    session.maxConnectionsPerSession = agentSettings.getMaxPersistentConnectionsPerSession();
                }

                int kickStartBytes = agentSettings.getKickstartBytes();
                if (kickStartBytes > 0) {
                    // Determine the minimum number of actual bytes that need to be sent to
                    // kickstart, taking into account transfer-encoding overhead.
                    try {
                        int chunkLengthHeaderSize = Integer.toHexString(kickStartBytes).getBytes("ASCII").length;
                        int chunkOverhead = chunkLengthHeaderSize + 4; // 4 for the 2 wrapping CRLF tokens.
                        int minimumKickstartBytes = kickStartBytes - chunkOverhead;
                        kickStartBytesToStream = new byte[(minimumKickstartBytes > 0) ? minimumKickstartBytes :
                                kickStartBytes];
                    }
                    catch (UnsupportedEncodingException ignore) {
                        kickStartBytesToStream = new byte[kickStartBytes];
                    }
                    Arrays.fill(kickStartBytesToStream, NULL_BYTE);
                }
            }

            // Now, check with the session before granting the streaming connection.
            synchronized(session) {
                ++session.streamingConnectionsCount;
                if (session.streamingConnectionsCount == session.maxConnectionsPerSession) {
                    thisThreadCanStream = true; // This thread got the last spot in the session.
                    session.canStream = false;
                } else if (session.streamingConnectionsCount > session.maxConnectionsPerSession) {
                    thisThreadCanStream = false; // This thread was beaten out for the last spot.
                    --session.streamingConnectionsCount;
                    synchronized(lock) {
                        // Decrement the endpoint count because we're not going to grant the streaming right to the client.
                        --streamingClientsCount;
                    }
                } else {
                    // We haven't hit the limit yet, allow this thread to stream.
                    thisThreadCanStream = true;
                }
            }

            // If the thread cannot wait due to session streaming connection
            // limit, inform the client and return.
            if (!thisThreadCanStream) {
                if (Log.isInfo())
                    log.info("Endpoint with id '" + getId() + "' cannot grant streaming connection to FlexClient with id '"
                            + flexClient.getId() + "' because " + UserAgentManager.MAX_PERSISTENT_CONNECTIONS_PER_SESSION + " limit of '" + session.maxConnectionsPerSession
                            + ((agentSettings != null) ? "' for user-agent '" + agentSettings.getMatchOn() + "'" : "") +  " has been reached." );
                try {
                 // Return an HTTP status code 400.
                    res.sendError(HttpServletResponse.SC_BAD_REQUEST);
                } catch (IOException ignore) {
                    // NOWARN
                }
                return;
            }

            Thread currentThread = Thread.currentThread();
            String threadName = currentThread.getName();
            EndpointPushNotifier notifier = null;
            boolean suppressIOExceptionLogging = false; // Used to suppress logging for IO exception.
            try {
                debug("goint to start streaming");
                currentThread.setName(threadName + "-in-streaming-mode");

                // Open and commit response headers and get output stream.
                if (addNoCacheHeaders)
                    addNoCacheHeaders(req, res);
                res.setContentType(getResponseContentType());
                res.setHeader("Connection", "close");
                res.setHeader("Transfer-Encoding", "chunked");
                ServletOutputStream os = res.getOutputStream();
                res.flushBuffer();
                debug("response headers commited");

                // If kickstart-bytes are specified, stream them.
                if (kickStartBytesToStream != null) {
                    if (Log.isDebug())
                        log.debug("Endpoint with id '" + getId() + "' is streaming " + kickStartBytesToStream.length
                                + " bytes (not counting chunk encoding overhead) to kick-start the streaming connection for FlexClient with id '"
                                + flexClient.getId() + "'.");

                    streamChunk(kickStartBytesToStream, os, res);
                    debug("kick bytes streamed");
                }

                // Setup serialization and type marshalling contexts
                setThreadLocals();
                debug("thread locals is good");

                // Activate streaming helper for this connection.
                // Watch out for duplicate stream issues.
                try {
                    notifier = new EndpointPushNotifier(this, flexClient);
                    debug("push notifier created");
                } catch (MessageException me) {
                    debug("got message exception");
                    if (me.getNumber() == 10033) { // It's a duplicate stream request from the same FlexClient. Leave the current stream in place and fault this.
                        if (Log.isWarn())
                            log.error("Endpoint with id '" + getId() + "' received a duplicate streaming connection request from, FlexClient with id '"
                                    + flexClient.getId() + "'. Faulting request.");

                        // Rollback counters and send an error response.
                        synchronized (lock) {
                            --streamingClientsCount;
                            canStream = (streamingClientsCount < maxStreamingClients);
                            synchronized (session) {
                                --session.streamingConnectionsCount;
                                session.canStream = (session.streamingConnectionsCount < session.maxConnectionsPerSession);
                            }
                        }
                        try {
                            res.sendError(HttpServletResponse.SC_BAD_REQUEST);
                        } catch (IOException ignore) {
                            // NOWARN
                        }
                        return; // Exit early.
                    }
                }
                if (getConnectionIdleTimeoutMinutes() > 0) {
                    notifier.setIdleTimeoutMinutes(getConnectionIdleTimeoutMinutes());
                    debug("set connection idle timeout minutes to " + getConnectionIdleTimeoutMinutes());
                }
                notifier.setLogCategory(getLogCategory());
                //monitorTimeout(notifier);
                currentStreamingRequests.put(notifier.getNotifierId(), notifier);

                // Push down an acknowledgement for the 'connect' request containing the unique id for this specific stream.
                AcknowledgeMessage connectAck = new AcknowledgeMessage();
                connectAck.setBody(notifier.getNotifierId());
                connectAck.setCorrelationId("open");
                ArrayList toPush = new ArrayList(1);
                toPush.add(connectAck);
                streamMessages(toPush, os, res);
                debug("acknowledge message streamed");

                // Output session level streaming count.
                debug("Number of streaming clients for FlexSession with id '"+ session.getId() +"' is " + session.streamingConnectionsCount + ".");

                // Output endpoint level streaming count.
                debug("Number of streaming clients for endpoint with id '"+ getId() +"' is " + streamingClientsCount + ".");

                // Now we ready to put push notifier in queue for async proccessing
                req.setAttribute("pushNotifier", notifier);
                req.setAttribute("flexClient", flexClient);
                debug("creating async context");
                AsyncContext actx = req.startAsync();
                actx.addListener(new AsyncListener() {
                    @Override
                    public void onTimeout(AsyncEvent event) throws IOException {
                        debug("AsyncContext Timeout! " + event.toString() );
                    }
                    
                    @Override
                    public void onStartAsync(AsyncEvent event) throws IOException {
                        debug("AsyncContext Start async! " + event.toString() );
                    }
                    
                    @Override
                    public void onError(AsyncEvent event) throws IOException {
                        debug("AsyncContext Error! " + event.toString() );
                    }
                    
                    @Override
                    public void onComplete(AsyncEvent event) throws IOException {
                        debug("AsyncContext Complete! " + event);
                        synchronized (lock) {
                            --streamingClientsCount;
                            canStream = (streamingClientsCount < maxStreamingClients);
                            synchronized (session) {
                                --session.streamingConnectionsCount;
                                session.canStream = (session.streamingConnectionsCount < session.maxConnectionsPerSession);
                            }
                        }
                        
                        EndpointPushNotifier notifier =
                            (EndpointPushNotifier) event.getSuppliedRequest().getAttribute("pushNotifier");

                        if (notifier != null && currentStreamingRequests != null) {
                            currentStreamingRequests.remove(notifier.getNotifierId());
                            notifier.close();
                        }
                        
                        synchronized (session) {
                            --session.streamingConnectionsCount;
                            session.canStream = (session.streamingConnectionsCount < session.maxConnectionsPerSession);
                        }
                        
                       //If the flex client closes the session the FlexSession would be null at this point -HT
                        if (session != null) {
                        	// Output session level streaming count.
                        	debug("Number of streaming clients for FlexSession with id '"+ session.getId() +"' is " + session.streamingConnectionsCount + ".");
                        } else {
                        	debug("FlexSession was closed and unable to determine the details.");
                        }
                        
                        // Output endpoint level streaming count.
                        debug("Number of streaming clients for endpoint with id '"+ getId() +"' is " + streamingClientsCount + ".");
                        
                    }
                });
                
                // Set the context timeout to the user specified value.
                
                debug ( "getConnectionIdleTimeoutMinutes = " + getConnectionIdleTimeoutMinutes() );
                if (getConnectionIdleTimeoutMinutes() > 0) {
                	actx.setTimeout( getConnectionIdleTimeoutMinutes() * 60 * 1000 );
                } else {
                	actx.setTimeout(3 * 60 * 1000); // 3 minutes
                }
                
                queue.add(actx);
                
                //If the notifier thread is not running then start it.
                if ( !notifierRunning ) {
                	debug( "starting notifierThread running");
                	notifierThread.execute( notifierRunnable );
                }
                
            } catch (IOException e) {
                if (Log.isWarn() && !suppressIOExceptionLogging)
                    log.warn("Streaming thread '" + threadName + "' for endpoint with id '" + getId() + "' is closing connection due to an IO error.", e);
            } catch ( Exception e ) {
            	e.printStackTrace();
            }
        } else { // Otherwise, client's streaming connection open request could not be granted.
            if (Log.isError()) {
                String logString = null;
                if (!canStream) {
                    logString = "Endpoint with id '" + getId() + "' cannot grant streaming connection to FlexClient with id '"
                    + flexClient.getId() + "' because max-streaming-clients limit of '"
                    + maxStreamingClients + "' has been reached.";
                } else if (!session.canStream) {
                    logString = "Endpoint with id '" + getId() + "' cannot grant streaming connection to FlexClient with id '"
                    + flexClient.getId() + "' because " + UserAgentManager.MAX_STREAMING_CONNECTIONS_PER_SESSION + " limit of '"
                    + session.maxConnectionsPerSession + "' has been reached.";
                }
                if (logString != null)
                    log.error(logString);
            } try {
                // Return an HTTP status code 400 to indicate that client request can't be processed.
                res.sendError(HttpServletResponse.SC_BAD_REQUEST);
            } catch (IOException ignore) {}
        }
    }

    /**
     * Helper method invoked by the endpoint request handler thread cycling in wait-notify.
     * Serializes messages and streams each to the client as a response chunk using streamChunk().
     *
     * @param messages The messages to serialize and push to the client.
     * @param os The output stream the chunk will be written to.
     * @param response The HttpServletResponse, used to flush the chunk to the client.
     */
    @SuppressWarnings("unchecked")
    protected abstract void streamMessages(List messages, ServletOutputStream os, HttpServletResponse response) throws IOException;

    //--------------------------------------------------------------------------
    //
    // Private methods.
    //
    //--------------------------------------------------------------------------

    /**
     * Utility method used at EndpointPushNotifier construction to monitor it for timeout.
     *
     * @param notifier The EndpointPushNotifier to monitor.
     */
    private void monitorTimeout(EndpointPushNotifier notifier) {
        if (pushNotifierTimeoutManager != null)
            pushNotifierTimeoutManager.scheduleTimeout(notifier);
    }
}
