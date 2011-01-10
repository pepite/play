package notifiers;

import play.Logger;
import play.mvc.Notifier;
import play.notifiers.Broadcast;

/**
 * Created by IntelliJ IDEA.
 * User: nicolasleroux
 * Date: 1/10/11
 * Time: 9:59 PM
 * To change this template use File | Settings | File Templates.
 */
public class Event extends Notifier {

    @Broadcast(all = true)
    public static void handleMessage(String text) {
        Logger.info("TEST");
        renderText(text);
    }

    public static void saysHi(String x) {

        renderText("Hi");
    }

}
