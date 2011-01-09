package play.server.websocket;

import org.apache.commons.io.IOUtils;
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
import play.mvc.ActionInvoker;
import play.mvc.Http;
import play.mvc.Router;
import play.mvc.Scope;
import play.mvc.results.NoResult;
import play.mvc.results.NotFound;
import play.mvc.results.Result;
import play.server.PlayHandler;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
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

            final Http.Response response = new Http.Response();
            final Http.Request request = new Http.Request();
            request.method = "WEBSOCKET";
            request.path = map.get(ctx).path;
            request.querystring = "";
            if (frame.isText()) {
                request.body = new ByteArrayInputStream(frame.getTextData().getBytes());
            } else if (frame.isBinary())  {
                request.body = new ByteArrayInputStream(frame.getBinaryData().array());
                request.isLoopback = true;
            }
            Http.Response.current.set(response);
            response.out = new ByteArrayOutputStream();
            boolean raw = false;
            for (PlayPlugin plugin : Play.plugins) {
                if (plugin.rawInvocation(request, response)) {
                    raw = true;
                    break;
                }
            }
            if (raw) {
                copyResponse(ctx, request, response, nettyRequest);
            } else {
                Invoker.invoke(new WebSocketInvocation(request, response, ctx, nettyRequest));
            }

        } catch (Exception ex) {
            ex.printStackTrace();
            serve500(ex, ctx, nettyRequest);
        }

    }

    public class WebSocketInvocation extends Invoker.Invocation {

        private final ChannelHandlerContext ctx;
        private final Http.Request request;
        private final Http.Response response;
        private final HttpRequest nettyRequest;
        private Broadcast broadcast;

        public WebSocketInvocation(Http.Request request, Http.Response response, ChannelHandlerContext ctx, HttpRequest nettyRequest) {
            this.ctx = ctx;
            this.request = request;
            this.response = response;
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
                Http.Request.current.set(request);
                Http.Response.current.set(response);

                Scope.Params.current.set(request.params);
                Scope.RenderArgs.current.set(new Scope.RenderArgs());
                Scope.RouteArgs.current.set(new Scope.RouteArgs());
                // 1. Route and resolve format if not already done
                if (request.action == null) {
                    for (PlayPlugin plugin : Play.plugins) {
                        plugin.routeRequest(request);
                    }
                    Router.Route route = Router.route(request);
                    for (PlayPlugin plugin : Play.plugins) {
                        plugin.onRequestRouting(route);
                    }
                }
                request.resolveFormat();

                // 2. Find the action method
                Method actionMethod = null;
                try {
                    Object[] ca = ActionInvoker.getActionMethod(request.action);
                    actionMethod = (Method) ca[1];
                    request.controller = ((Class) ca[0]).getName().substring(12).replace("$", "");
                    request.controllerClass = ((Class) ca[0]);
                    request.actionMethod = actionMethod.getName();
                    request.action = request.controller + "." + request.actionMethod;
                    request.invokedMethod = actionMethod;
                    broadcast = actionMethod.getAnnotation(Broadcast.class);
                } catch (ActionNotFoundException e) {
                    Logger.error(e, "%s action not found", e.getAction());
                    throw new NotFound(String.format("%s action not found", e.getAction()));
                }

                ControllersEnhancer.ControllerInstrumentation.stopActionCall();
                for (PlayPlugin plugin : Play.plugins) {
                    plugin.beforeActionInvocation(actionMethod);
                }
                ControllersEnhancer.ControllerInstrumentation.initActionCall();

                if (request.isLoopback) {
                    ActionInvoker.inferResult(actionMethod.invoke(null, IOUtils.toByteArray(request.body)));
                } else {
                    ActionInvoker.inferResult(actionMethod.invoke(null, IOUtils.toString(request.body)));
                }

            } catch (InvocationTargetException ex) {
                ex.printStackTrace();
                // It's a Result ? (expected)
                Result result = new NoResult();
                if (ex.getTargetException() instanceof Result) {
                    result = (Result) ex.getTargetException();
                }

                Logger.info("result " + result);
                for (PlayPlugin plugin : Play.plugins) {
                    plugin.onActionInvocationResult(result);
                }

                result.apply(request, response);

                for (PlayPlugin plugin : Play.plugins) {
                    plugin.afterActionInvocation();
                }
            }
        }


        @Override
        public void onSuccess() throws Exception {
            super.onSuccess();
            // Loop over all the channels and determine which one
            Logger.info("response is [" + new String(response.out.toByteArray()) + "]");
            if (broadcast !=  null) {
                for (WebSocketChannel ws : map.values()) {
                    if (ws.path.equals(request.path)) {
                        for (Channel c : ws.channels) {
                            if (c != ctx.getChannel() && !broadcast.all()) {
                                c.write(new DefaultWebSocketFrame(new String(response.out.toByteArray())));
                            } else if (broadcast.all()) {
                                c.write(new DefaultWebSocketFrame(new String(response.out.toByteArray())));
                            }
                        }
                    }
                }
            } else {
                ctx.getChannel().write(new DefaultWebSocketFrame(new String(response.out.toByteArray())));
            }
        }

    }

    private String getWebSocketLocation(HttpRequest req) {
        return "ws://" + req.getHeader(HttpHeaders.Names.HOST) + req.getUri();
    }

}

