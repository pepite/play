package play.notifiers;

import play.mvc.Notifier;

/**
 * Created by IntelliJ IDEA.
 * User: nicolasleroux
 * Date: 1/10/11
 * Time: 8:55 PM
 * To change this template use File | Settings | File Templates.
 */
public class NoRender extends Render {

    public NoRender() {
    }

    @Override
    public void apply(Notifier.Inbound inbound, Notifier.Outbound outbound) {}

}