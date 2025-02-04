/*******************************************************************************
 * Copyright (c) 2015-2018 Skymind, Inc.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Apache License, Version 2.0 which is available at
 * https://www.apache.org/licenses/LICENSE-2.0.
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 *
 * SPDX-License-Identifier: Apache-2.0
 ******************************************************************************/

package org.deeplearning4j.ui.play;

import com.beust.jcommander.JCommander;
import com.beust.jcommander.Parameter;
import com.beust.jcommander.ParameterException;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.deeplearning4j.api.storage.StatsStorage;
import org.deeplearning4j.api.storage.StatsStorageEvent;
import org.deeplearning4j.api.storage.StatsStorageListener;
import org.deeplearning4j.api.storage.StatsStorageRouter;
import org.deeplearning4j.config.DL4JSystemProperties;
import org.deeplearning4j.ui.SameDiffModule;
import org.deeplearning4j.ui.api.Route;
import org.deeplearning4j.ui.api.UIModule;
import org.deeplearning4j.ui.api.UIServer;
import org.deeplearning4j.ui.i18n.DefaultI18N;
import org.deeplearning4j.ui.i18n.I18NProvider;
import org.deeplearning4j.ui.module.convolutional.ConvolutionalListenerModule;
import org.deeplearning4j.ui.module.defaultModule.DefaultModule;
import org.deeplearning4j.ui.module.remote.RemoteReceiverModule;
import org.deeplearning4j.ui.module.train.TrainModule;
import org.deeplearning4j.ui.module.tsne.TsneModule;
import org.deeplearning4j.ui.play.staticroutes.Assets;
import org.deeplearning4j.ui.storage.FileStatsStorage;
import org.deeplearning4j.ui.storage.InMemoryStatsStorage;
import org.deeplearning4j.ui.storage.impl.QueueStatsStorageListener;
import org.deeplearning4j.util.DL4JFileUtils;
import org.nd4j.linalg.function.Function;
import org.nd4j.linalg.primitives.Pair;
import play.BuiltInComponents;
import play.Mode;
import play.routing.Router;
import play.routing.RoutingDsl;
import play.server.Server;

import java.io.File;
import java.util.*;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;

import static play.mvc.Results.ok;


/**
 * A UI server based on the Play framework
 *
 * @author Alex Black
 */
@Slf4j
@Data
public class PlayUIServer extends UIServer {

    /**
     * @deprecated Use {@link DL4JSystemProperties#UI_SERVER_PORT_PROPERTY}
     */
    @Deprecated
    public static final String UI_SERVER_PORT_PROPERTY = DL4JSystemProperties.UI_SERVER_PORT_PROPERTY;
    public static final int DEFAULT_UI_PORT = 9000;

    public static final String ASSETS_ROOT_DIRECTORY = "deeplearning4jUiAssets/";

    private Server server;
    private boolean stopped;
    private final BlockingQueue<StatsStorageEvent> eventQueue = new LinkedBlockingQueue<>();
    private List<Pair<StatsStorage, StatsStorageListener>> listeners = new CopyOnWriteArrayList<>();
    private List<StatsStorage> statsStorageInstances = new CopyOnWriteArrayList<>();

    private List<UIModule> uiModules = new CopyOnWriteArrayList<>();
    private RemoteReceiverModule remoteReceiverModule;
    //typeIDModuleMap: Records which modules are registered for which type IDs
    private Map<String, List<UIModule>> typeIDModuleMap = new ConcurrentHashMap<>();

    private long uiProcessingDelay = 500; //500ms. TODO make configurable
    private final AtomicBoolean shutdown = new AtomicBoolean(false);

    private Thread uiEventRoutingThread;

    @Parameter(names = {"-r", "--enableRemote"}, description = "Whether to enable remote or not", arity = 1)
    private boolean enableRemote;

    @Parameter(names = {"-p", "--uiPort"}, description = "Custom HTTP port for UI", arity = 1)
    private int port = DEFAULT_UI_PORT;

    @Parameter(names = {"-f", "--customStatsFile"}, description = "Path to create custom stats file (remote only)", arity = 1)
    private String customStatsFile;

    @Parameter(names = {"-m", "--multiSession"}, description = "Whether to enable multiple separate browser sessions or not", arity = 1)
    private boolean multiSession;

    private StatsStorageLoader statsStorageLoader;

    public PlayUIServer() {
        this(DEFAULT_UI_PORT);
    }

    public PlayUIServer(int port) {
        this(port, false);
    }

    /**
     * Create {@code PlayUIServer} at given port in given mode. Note: to start the server, run {@link #runMain(String[])}
     * @param port port that the server will listen on
     * @param multiSession in multi-session mode, multiple training sessions can be visualized in separate browser tabs.
     *                     <br/>URL path will include session ID as a parameter, i.e.: /train becomes /train/:sessionId
     */
    public PlayUIServer(int port, boolean multiSession) {
        this.port = port;
        this.multiSession = multiSession;
    }

    /**
     * Auto-attach StatsStorage if an unknown session ID is passed as URL path parameter in multi-session mode
     * @param statsStorageProvider function that returns a StatsStorage containing the given session ID
     */
    public void autoAttachStatsStorageBySessionId(Function<String, StatsStorage> statsStorageProvider) {
        if (statsStorageProvider != null) {
            this.statsStorageLoader = new StatsStorageLoader(statsStorageProvider);
        }
    }

    public void runMain(String[] args) {
        JCommander jcmdr = new JCommander(this);

        try {
            jcmdr.parse(args);
        } catch (ParameterException e) {
            //User provides invalid input -> print the usage info
            jcmdr.usage();
            try {
                Thread.sleep(500);
            } catch (Exception e2) {
            }
            System.exit(1);
        }

        if(((DefaultI18N)I18NProvider.getInstance()).noI18NData()){
            Throwable t = ((DefaultI18N)I18NProvider.getInstance()).getLanguageLoadingException();
            if(t != null){
                throw new RuntimeException("Exception encountered during loading UI Language (Internationalization) data", t);
            }

            log.error("Error loading UI Language (Internationalization) data: no language resource data files were" +
                    "found on the classpath. This usually occurs when running DL4J's UI from an uber-jar, which was " +
                    "built incorrectly (without language resource files). See https://deeplearning4j.org/docs/latest/deeplearning4j-nn-visualization#issues" +
                    "for more details");
            System.exit(1);
        }

        String portProperty = System.getProperty(DL4JSystemProperties.UI_SERVER_PORT_PROPERTY);
        if (portProperty != null) {
            try {
                port = Integer.parseInt(portProperty);
            } catch (NumberFormatException e) {
                log.warn("Could not parse UI port property \"{}\" with value \"{}\"", DL4JSystemProperties.UI_SERVER_PORT_PROPERTY,
                                portProperty, e);
            }
        }


        //Set play secret key, if required
        //http://www.playframework.com/documentation/latest/ApplicationSecret
        String crypto = System.getProperty("play.crypto.secret");
        if (crypto == null || "changeme".equals(crypto) || "".equals(crypto) ) {
            byte[] newCrypto = new byte[1024];

            new Random().nextBytes(newCrypto);

            String base64 = Base64.getEncoder().encodeToString(newCrypto);
            System.setProperty("play.crypto.secret", base64);
        }


        try {
            server = Server.forRouter(Mode.PROD, port, this::createRouter);
        } catch (Throwable e){
            if(e.getMessage().contains("'play.crypto.provider")){
                //Usual cause: user's uber-jar does not include application.conf
                log.error("Error starting UI server due to missing play.crypto.provider config: This usually occurs due to missing" +
                        " application.conf file. DL4J's UI (based on the Play framework) requires this file in order" +
                        " to run. File can be missing due to incorrect creation of uber-jars that do not include resource" +
                        " files. See https://deeplearning4j.org/docs/latest/deeplearning4j-nn-visualization#issues for more information", e);
            } else {
                log.error("Unknown error when starting UI server",e);
            }
            throw e;
        }

        log.info("DL4J UI Server started at {}", getAddress());

        uiEventRoutingThread = new Thread(new StatsEventRouterRunnable());
        uiEventRoutingThread.setDaemon(true);
        uiEventRoutingThread.start();
        if (enableRemote && customStatsFile != null) {
            enableRemoteListener(new FileStatsStorage(new File(customStatsFile)), true);
        }
        else if(enableRemote) {
            try {
                File tempStatsFile = DL4JFileUtils.createTempFile("dl4j", "UIstats");
                tempStatsFile.delete();
                tempStatsFile.deleteOnExit();
                enableRemoteListener(new FileStatsStorage(tempStatsFile), true);
            } catch(Exception e) {
                log.error("Failed to create temporary file for stats storage",e);
                System.exit(1);
            }
        }

        setStopped(false);
    }

    protected Router createRouter(BuiltInComponents builtInComponents){
        RoutingDsl routingDsl = RoutingDsl.fromComponents(builtInComponents);

        //Set up index page and assets routing
        //The definitions and FunctionUtil may look a bit weird here... this is used to translate implementation independent
        // definitions (i.e., Java Supplier, Function etc interfaces) to the Play-specific versions
        //This way, routing is not directly dependent ot Play API. Furthermore, Play 2.5 switches to using these Java interfaces
        // anyway; thus switching 2.5 should be as simple as removing the FunctionUtil calls...
        if (multiSession) {
            routingDsl.GET("/setlang/:sessionId/:to").routingTo((request, sid, to) -> {
                I18NProvider.getInstance(sid.toString()).setDefaultLanguage(to.toString());
                return ok();
            });
        } else {
            routingDsl.GET("/setlang/:to").routingTo((request, to) -> {
                I18NProvider.getInstance().setDefaultLanguage(to.toString());
                return ok();
            });
        }
        routingDsl.GET("/assets/*file").routingTo((request, file) -> Assets.assetRequest(ASSETS_ROOT_DIRECTORY, file.toString()));

        uiModules.add(new DefaultModule(multiSession)); //For: navigation page "/"
        uiModules.add(new TrainModule(multiSession, statsStorageLoader, this::getAddress));
        uiModules.add(new ConvolutionalListenerModule());
        uiModules.add(new TsneModule());
        uiModules.add(new SameDiffModule());
        remoteReceiverModule = new RemoteReceiverModule();
        uiModules.add(remoteReceiverModule);

        //Check service loader mechanism (Arbiter UI, etc) for modules
        modulesViaServiceLoader(uiModules);

        for (UIModule m : uiModules) {
            List<Route> routes = m.getRoutes();
            for (Route r : routes) {
                RoutingDsl.PathPatternMatcher ppm = routingDsl.match(r.getHttpMethod().name(), r.getRoute());
                switch (r.getFunctionType()) {
                    case Supplier:
                        ppm.routingTo(request -> r.getSupplier().get());
                        break;
                    case Function:
                        ppm.routingTo((request, arg) -> r.getFunction().apply(arg.toString()));
                        break;
                    case BiFunction:
                        ppm.routingTo((request, arg0, arg1) -> r.getFunction2().apply(arg0.toString(), arg1.toString()));
                        break;
                    case Request0Function:
                        ppm.routingTo(request -> r.getRequest0Function().apply(request));
                        break;
                    case Request1Function:
                        ppm.routingTo((request, arg0) -> r.getRequest1Function().apply(request, arg0.toString()));
                        break;
                    case Function3:
                    default:
                        throw new RuntimeException("Not yet implemented");
                }
            }

            //Determine which type IDs this module wants to receive:
            List<String> typeIDs = m.getCallbackTypeIDs();
            for (String typeID : typeIDs) {
                List<UIModule> list = typeIDModuleMap.get(typeID);
                if (list == null) {
                    list = Collections.synchronizedList(new ArrayList<>());
                    typeIDModuleMap.put(typeID, list);
                }
                list.add(m);
            }
        }
        Router router = routingDsl.build();
        return router;
    }

    @Override
    public String getAddress() {
        String addr = server.mainAddress().toString();
        if (addr.startsWith("/0:0:0:0:0:0:0:0")) {
            int last = addr.lastIndexOf(':');
            if (last > 0) {
                addr = "http://localhost:" + addr.substring(last + 1);
            }
        }
        return addr;
    }

    private void modulesViaServiceLoader(List<UIModule> uiModules) {

        ServiceLoader<UIModule> sl = ServiceLoader.load(UIModule.class);
        Iterator<UIModule> iter = sl.iterator();

        if (!iter.hasNext()) {
            return;
        }

        while (iter.hasNext()) {
            UIModule m = iter.next();
            Class<?> c = m.getClass();
            boolean foundExisting = false;
            for(UIModule mExisting : uiModules){
                if(mExisting.getClass() == c){
                    foundExisting = true;
                    break;
                }
            }

            if(!foundExisting) {
                log.debug("Loaded UI module via service loader: {}", m.getClass());
                uiModules.add(m);
            }
        }
    }


    public static void main(String[] args) {
        new PlayUIServer().runMain(args);
    }

    @Override
    public int getPort() {
        return port;
    }

    @Override
    public synchronized void attach(StatsStorage statsStorage) {
        if (statsStorage == null)
            throw new IllegalArgumentException("StatsStorage cannot be null");
        if (statsStorageInstances.contains(statsStorage))
            return;
        StatsStorageListener listener = new QueueStatsStorageListener(eventQueue);
        listeners.add(new Pair<>(statsStorage, listener));
        statsStorage.registerStatsStorageListener(listener);
        statsStorageInstances.add(statsStorage);

        for (UIModule uiModule : uiModules) {
            uiModule.onAttach(statsStorage);
        }

        log.info("StatsStorage instance attached to UI: {}", statsStorage);
    }

    @Override
    public synchronized void detach(StatsStorage statsStorage) {
        if (statsStorage == null)
            throw new IllegalArgumentException("StatsStorage cannot be null");
        if (!statsStorageInstances.contains(statsStorage))
            return; //No op
        boolean found = false;
        for (Iterator<Pair<StatsStorage, StatsStorageListener>> iterator = listeners.iterator(); iterator.hasNext();) {
            Pair<StatsStorage, StatsStorageListener> p = iterator.next();
            if (p.getFirst() == statsStorage) { //Same object, not equality
                statsStorage.deregisterStatsStorageListener(p.getSecond());
                listeners.remove(p);
                found = true;
            }
        }
        statsStorageInstances.remove(statsStorage);
        for (UIModule uiModule : uiModules) {
            uiModule.onDetach(statsStorage);
        }
        for (String sessionId : statsStorage.listSessionIDs()) {
            I18NProvider.removeInstance(sessionId);
        }
        if (found) {
            log.info("StatsStorage instance detached from UI: {}", statsStorage);
        }
    }

    @Override
    public boolean isAttached(StatsStorage statsStorage) {
        return statsStorageInstances.contains(statsStorage);
    }

    @Override
    public List<StatsStorage> getStatsStorageInstances() {
        return new ArrayList<>(statsStorageInstances);
    }

    @Override
    public void enableRemoteListener() {
        if (remoteReceiverModule == null)
            remoteReceiverModule = new RemoteReceiverModule();
        if (remoteReceiverModule.isEnabled())
            return;
        enableRemoteListener(new InMemoryStatsStorage(), true);
    }

    @Override
    public void enableRemoteListener(StatsStorageRouter statsStorage, boolean attach) {
        remoteReceiverModule.setEnabled(true);
        remoteReceiverModule.setStatsStorage(statsStorage);
        if (attach && statsStorage instanceof StatsStorage) {
            attach((StatsStorage) statsStorage);
        }
    }

    @Override
    public void disableRemoteListener() {
        remoteReceiverModule.setEnabled(false);
    }

    @Override
    public boolean isRemoteListenerEnabled() {
        return remoteReceiverModule.isEnabled();
    }

    @Override
    public void stop() {
        if (server != null) {
            server.stop();
            setStopped(true);
        }

    }


    private class StatsEventRouterRunnable implements Runnable {

        @Override
        public void run() {
            try {
                runHelper();
            } catch (Exception e) {
                log.error("Unexpected exception from Event routing runnable", e);
            }
        }

        private void runHelper() throws Exception {
            log.debug("PlayUIServer.StatsEventRouterRunnable started");
            //Idea: collect all event stats, and route them to the appropriate modules
            while (!shutdown.get()) {

                List<StatsStorageEvent> events = new ArrayList<>();
                StatsStorageEvent sse = eventQueue.take(); //Blocking operation
                events.add(sse);
                eventQueue.drainTo(events); //Non-blocking

                for (UIModule m : uiModules) {

                    List<String> callbackTypes = m.getCallbackTypeIDs();
                    List<StatsStorageEvent> out = new ArrayList<>();
                    for (StatsStorageEvent e : events) {
                        if (callbackTypes.contains(e.getTypeID())
                                && statsStorageInstances.contains(e.getStatsStorage())) {
                            out.add(e);
                        }
                    }

                    m.reportStorageEvents(out);
                }

                events.clear();

                try {
                    Thread.sleep(uiProcessingDelay);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    if (!shutdown.get()) {
                        throw new RuntimeException("Unexpected interrupted exception", e);
                    }
                }
            }
        }
    }

    /**
     * Loader that attaches {@code StatsStorage} provided by {@code #statsStorageProvider} for the given session ID
     */
    private class StatsStorageLoader implements Function<String, Boolean> {

        Function<String, StatsStorage> statsStorageProvider;

        StatsStorageLoader(Function<String, StatsStorage> statsStorageProvider) {
            this.statsStorageProvider = statsStorageProvider;
        }

        @Override
        public Boolean apply(String sessionId) {
            log.info("Loading StatsStorage via StatsStorageProvider for session ID (" + sessionId + ").");
            StatsStorage statsStorage = statsStorageProvider.apply(sessionId);
            if (statsStorage != null) {
                if (statsStorage.sessionExists(sessionId)) {
                    attach(statsStorage);
                    return true;
                }
                log.info("Failed to load StatsStorage via StatsStorageProvider for session ID. " +
                        "Session ID (" + sessionId + ") does not exist in StatsStorage.");
                return false;
            } else {
                log.info("Failed to load StatsStorage via StatsStorageProvider for session ID (" + sessionId + "). " +
                        "StatsStorageProvider returned null.");
                return false;
            }
        }
    }

}
