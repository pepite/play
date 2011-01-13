package play.notifiers;

import play.mvc.Notifier;

/**
 * Created by IntelliJ IDEA.
 * User: nicolasleroux
 * Date: 1/10/11
 * Time: 8:55 PM
 * To change this template use File | Settings | File Templates.
 */
public class Render extends RuntimeException {

    public byte[] content;

    public Render() {
        super();
    }


    public Render(byte[] content) {
        super();
        this.content = content;
    }

    public void apply(Notifier.Inbound inbound, Notifier.Outbound outbound) {
        outbound.content = content;
    }


}