package controllers;

import play.mvc.Controller;
import play.server.websocket.Broadcast;

public class Application extends Controller {

    public static void index() {
        render();
    }

    @Broadcast(all = true)
    public static void handleMessage(String text) {

        render(text);
    }

    public static void saysHi(String x) {

        renderText("Hi");
    }

}