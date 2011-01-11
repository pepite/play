package play.server.websocket;

import javassist.CtMethod;
import org.jboss.netty.buffer.ChannelBuffer;
import org.jboss.netty.buffer.ChannelBuffers;
import org.jboss.netty.channel.*;
import org.jboss.netty.channel.group.ChannelGroup;
import org.jboss.netty.channel.group.DefaultChannelGroup;
import org.jboss.netty.handler.codec.http.*;
import org.jboss.netty.handler.codec.http.HttpHeaders.Names;
import org.jboss.netty.handler.codec.http.HttpHeaders.Values;
import org.jboss.netty.handler.codec.http.websocket.DefaultWebSocketFrame;
import org.jboss.netty.handler.codec.http.websocket.WebSocketFrame;
import org.jboss.netty.handler.codec.http.websocket.WebSocketFrameDecoder;
import org.jboss.netty.handler.codec.http.websocket.WebSocketFrameEncoder;
import play.Invoker;
import play.Logger;
import play.Play;
import play.PlayPlugin;
import play.classloading.enhancers.ControllersEnhancer;
import play.exceptions.ActionNotFoundException;
import play.mvc.*;
import play.mvc.results.NotFound;
import play.mvc.results.Redirect;
import play.mvc.results.Result;
import play.notifiers.Broadcast;
import play.notifiers.NoRender;
import play.notifiers.Render;
import play.server.PlayHandler;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.security.MessageDigest;
import java.util.HashMap;

import static org.jboss.netty.handler.codec.http.HttpHeaders.Names.*;
import static org.jboss.netty.handler.codec.http.HttpHeaders.Values.WEBSOCKET;
import static org.jboss.netty.handler.codec.http.HttpVersion.HTTP_1_1;

/**
 *
 */
public class WebSocketServerHandler extends PlayHandler {

    public final static HttpMethod WEBSOCKET_METHOD = new HttpMethod("WEBSOCKET");


    @Override
    public void channelDisconnected(
            ChannelHandlerContext ctx, ChannelStateEvent e) throws Exception {
        Logger.info("WebSocketServerHandler: channelDisconnected");
        map.get(ctx).channels.remove(e.getChannel());
    }


    @Override
    public void messageReceived(ChannelHandlerContext ctx, MessageEvent e) throws Exception {
        Object msg = e.getMessage();
        if (msg instanceof HttpRequest) {
            HttpRequest req = (HttpRequest) msg;
            boolean isWebsocketRequest = handleHttpRequest(ctx, req, e);

            if (!isWebsocketRequest) {
                super.messageReceived(ctx, e);
            }
        } else if (msg instanceof WebSocketFrame) {
            WebSocketFrame frame = (WebSocketFrame) msg;

            handleWebSocketFrame(ctx, frame);
        }
    }

    public static HashMap<ChannelHandlerContext, WebSocketChannel> map = new HashMap<ChannelHandlerContext, WebSocketChannel>();

    public class WebSocketChannel {
        public String path;
        public ChannelGroup channels = new DefaultChannelGroup();

        public WebSocketChannel(String path) {
            this.path = path;
        }
    }

    private boolean handleHttpRequest(ChannelHandlerContext ctx, HttpRequest req, MessageEvent e) throws Exception {
        Logger.info("WebSocketServerHandler: handleHttpRequest");

        // Serve the WebSocket handshake request.
        // TODO: Check that we have a route?
        if (Values.UPGRADE.equalsIgnoreCase(req.getHeader(CONNECTION)) &&
                WEBSOCKET.equalsIgnoreCase(req.getHeader(Names.UPGRADE))) {

            // Create the WebSocket handshake response.
            HttpResponse res = new DefaultHttpResponse(
                    HTTP_1_1,
                    new HttpResponseStatus(101, "Web Socket Protocol Handshake"));
            res.addHeader(Names.UPGRADE, WEBSOCKET);
            res.addHeader(CONNECTION, Values.UPGRADE);

            // Fill in the headers and contents depending on handshake method.
            if (req.containsHeader(SEC_WEBSOCKET_KEY1) &&
                    req.containsHeader(SEC_WEBSOCKET_KEY2)) {
                // New handshake method with a challenge:
                res.addHeader(SEC_WEBSOCKET_ORIGIN, req.getHeader(ORIGIN));
                res.addHeader(SEC_WEBSOCKET_LOCATION, getWebSocketLocation(req));
                String protocol = req.getHeader(SEC_WEBSOCKET_PROTOCOL);
                if (protocol != null) {
                    res.addHeader(SEC_WEBSOCKET_PROTOCOL, protocol);
                }

                // Calculate the answer of the challenge.
                String key1 = req.getHeader(SEC_WEBSOCKET_KEY1);
                String key2 = req.getHeader(SEC_WEBSOCKET_KEY2);
                int a = (int) (Long.parseLong(key1.replaceAll("[^0-9]", "")) / key1.replaceAll("[^ ]", "").length());
                int b = (int) (Long.parseLong(key2.replaceAll("[^0-9]", "")) / key2.replaceAll("[^ ]", "").length());
                long c = req.getContent().readLong();
                ChannelBuffer input = ChannelBuffers.buffer(16);
                input.writeInt(a);
                input.writeInt(b);
                input.writeLong(c);
                ChannelBuffer output = ChannelBuffers.wrappedBuffer(
                        MessageDigest.getInstance("MD5").digest(input.array()));
                res.setContent(output);
            } else {
                // Old handshake method with no challenge:
                res.addHeader(WEBSOCKET_ORIGIN, req.getHeader(ORIGIN));
                res.addHeader(WEBSOCKET_LOCATION, getWebSocketLocation(req));
                String protocol = req.getHeader(WEBSOCKET_PROTOCOL);
                if (protocol != null) {
                    res.addHeader(WEBSOCKET_PROTOCOL, protocol);
                }
            }

            // Upgrade the connection and send the handshake response.
            ChannelPipeline p = ctx.getChannel().getPipeline();
            p.remove("aggregator");
            p.replace("decoder", "wsdecoder", new WebSocketFrameDecoder());

            ctx.getChannel().write(res);

            // Call the annotated method(s) from the controller(s)
            // We only want to add channels for websocket
            map.put(ctx, new WebSocketChannel(req.getUri()));
            map.get(ctx).channels.add(e.getChannel());

            p.replace("encoder", "wsencoder", new WebSocketFrameEncoder());
            req.setMethod(WEBSOCKET_METHOD);
            return true;
        }

        return false;
    }

    private void handleWebSocketFrame(ChannelHandlerContext ctx, WebSocketFrame frame) {
        Logger.info("WebSocketServerHandler: handleWebSocketFrame");

        HttpRequest nettyRequest = new DefaultHttpRequest(HttpVersion.HTTP_1_1, WEBSOCKET_METHOD, "");
        try {

            Notifier.Inbound inbound = new Notifier.Inbound();
            Notifier.Outbound outbound = new Notifier.Outbound();

            inbound.path = map.get(ctx).path;
            // Resolve action
            Logger.trace("parseRequest: begin");
            Logger.trace("parseRequest: URI = " + nettyRequest.getUri());

            Router.Route route = Router.route(inbound);
            inbound.action = route.action;

            if (frame.isText()) {
                inbound.content = frame.getTextData().getBytes();
            } else if (frame.isBinary()) {
                inbound.content = frame.getBinaryData().array();
            }
            Notifier.Outbound.current.set(outbound);
            // TODO: Add support for plugin?
            Invoker.invoke(new WebSocketInvocation(inbound, outbound, ctx, nettyRequest));

        } catch (Exception ex) {
            ex.printStackTrace();
            serve500(ex, ctx, nettyRequest);
        }

    }

    public class WebSocketInvocation extends Invoker.Invocation {

        private final ChannelHandlerContext ctx;
        private final Notifier.Inbound inbound;
        private final Notifier.Outbound outbound;
        private final HttpRequest nettyRequest;

        public WebSocketInvocation(Notifier.Inbound inbound, Notifier.Outbound outbound, ChannelHandlerContext ctx, HttpRequest nettyRequest) {
            this.ctx = ctx;
            this.inbound = inbound;
            this.outbound = outbound;
            this.nettyRequest = nettyRequest;
        }

        @Override
        public boolean init() {

            return true;
        }

        @Override
        public void run() {
            try {
                Logger.trace("run: begin");
                super.run();
            } catch (Exception e) {
                // TODO: Check this
                serve500(e, ctx, nettyRequest);
            }
            Logger.trace("run: end");
        }

        @Override
        public void execute() throws Exception {
            if (!ctx.getChannel().isConnected()) {
                try {
                    ctx.getChannel().close();
                } catch (Throwable e) {
                    // Ignore
                }
                return;
            }

            try {
                Notifier.Inbound.current.set(inbound);
                Notifier.Outbound.current.set(outbound);

                Scope.RenderArgs.current.set(new Scope.RenderArgs());
                Scope.RouteArgs.current.set(new Scope.RouteArgs());

                // 2. Find the action method
                Method actionMethod = null;
                try {
                    Object[] ca = Notifier.getActionMethod(inbound.action);
                    actionMethod = (Method) ca[1];
                    // notifiers.
                    inbound.controller = ((Class) ca[0]).getName().replace("$", "");
                    inbound.controllerClass = ((Class) ca[0]);
                    inbound.actionMethod = actionMethod.getName();
                    inbound.action = inbound.controller + "." + inbound.actionMethod;
                    inbound.invokedMethod = actionMethod;
                    inbound.broadcast = actionMethod.getAnnotation(Broadcast.class);
                    Logger.info("action is [" + inbound.action + "]  controller [" + inbound.controller + "] broadcast [" + inbound.broadcast + "]");
                } catch (ActionNotFoundException e) {
                    Logger.error(e, "%s action not found", e.getAction());
                    throw new NotFound(String.format("%s action not found", e.getAction()));
                }

                // ControllersEnhancer.ControllerInstrumentation.stopActionCall();
                for (PlayPlugin plugin : Play.plugins) {
                    plugin.beforeActionInvocation(actionMethod);
                }
                // ControllersEnhancer.ControllerInstrumentation.initActionCall();

                // string or byte[]?
                Logger.info("content is [" + new String(inbound.content) + "]");
                // Make sure the method is embedded with @ByPass
                Field f = inbound.controllerClass.getField("redirect");
                f.setAccessible(true);
                f.setBoolean(null, false);
                actionMethod.invoke(null, new String(inbound.content));
                f.setBoolean(null, true);

            } catch (InvocationTargetException ex) {
                ex.printStackTrace();
                // It's a Result ? (expected)
                if (ex.getTargetException() instanceof Render) {
                    Render result = (Render) ex.getTargetException();
                    result.apply(inbound, outbound);
                } else if (ex.getTargetException() instanceof Result) {
                    Result result = (Result) ex.getTargetException();
                    // Simulate request
                    Http.Request request = new Http.Request();
                    request.controller = inbound.controller;
                    request.controllerClass = inbound.controllerClass;
                    request.actionMethod = inbound.actionMethod;
                    request.action = inbound.action;
                    request.invokedMethod = inbound.invokedMethod;
                    request.body = new ByteArrayInputStream(inbound.content);
                    Http.Response response = new Http.Response();
                    response.out = new ByteArrayOutputStream();
                    if (!(result instanceof Redirect))
                        result.apply(request, response);
                }

                for (PlayPlugin plugin : Play.plugins) {
                    plugin.afterActionInvocation();
                }
            }
        }


        @Override
        public void onSuccess() throws Exception {
            super.onSuccess();
            // Loop over all the channels and determine which one
            Logger.info("response is [" + new String(outbound.content) + "]  broadcast [" + inbound.broadcast + "]");
            if (inbound.broadcast != null) {
                for (WebSocketChannel ws : map.values()) {
                    if (ws.path.equals(inbound.path)) {
                        for (Channel c : ws.channels) {
                            if (c != ctx.getChannel() && !inbound.broadcast.all()) {
                                c.write(new DefaultWebSocketFrame(new String(outbound.content)));
                            } else if (inbound.broadcast.all()) {
                                c.write(new DefaultWebSocketFrame(new String(outbound.content)));
                            }
                        }
                    }
                }
            } else {
                ctx.getChannel().write(new DefaultWebSocketFrame(new String(outbound.content)));
            }
        }

    }

    public static void write(String path, byte[] message) {
        for (WebSocketChannel ws : map.values()) {
            if (ws.path.equals(path)) {
                for (Channel c : ws.channels) {
                    c.write(new DefaultWebSocketFrame(new String(message)));
                }
            }
        }

    }

    private String getWebSocketLocation(HttpRequest req) {
        return "ws://" + req.getHeader(HttpHeaders.Names.HOST) + req.getUri();
    }

}

