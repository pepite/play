package play.mvc;

import com.google.gson.Gson;
import play.Logger;
import play.Play;
import play.classloading.enhancers.LocalvariablesNamesEnhancer;
import play.data.validation.Validation;
import play.exceptions.ActionNotFoundException;
import play.exceptions.PlayException;
import play.exceptions.TemplateNotFoundException;
import play.mvc.results.RenderJson;
import play.notifiers.Broadcast;
import play.notifiers.Render;
import play.templates.Template;
import play.templates.TemplateLoader;
import play.utils.Java;

import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 *
 */
public class Notifier {


    protected static Inbound inbound = null;

    protected static Outbound outbound = null;

    // TODO: renderArgs?


    public static class Inbound {

        public String path;
        public String controller;
        public Class controllerClass;
        public String actionMethod;
        public String action;
        public Method invokedMethod;
        public byte[] content;
        public Broadcast broadcast = null;

        /**
         * Bind to thread
         */
        public static ThreadLocal<Inbound> current = new ThreadLocal<Inbound>();

        /**
         * Retrieve the current request
         *
         * @return the current request
         */
        public static Inbound current() {
            return current.get();
        }

    }

    public static class Outbound {

        public Broadcast broadcast = null;
        public byte[] content = new byte[0];

        public static ThreadLocal<Outbound> current = new ThreadLocal<Outbound>();

        public static Outbound current() {
            return current.get();
        }
    }

    /**
     * Return a 200 OK text/plain response
     *
     * @param text The response content
     */
    protected static void renderText(Object text) {
        throw new Render(text == null ? new byte[0] : text.toString().getBytes());
    }


    /**
     * Render a 200 OK application/json response
     *
     * @param jsonString The JSON string
     */
    protected static void renderJSON(String jsonString) {
        throw new Render(jsonString.getBytes());
    }

    /**
     * Render a 200 OK application/json response
     *
     * @param o The Java object to serialize
     */
    protected static void renderJSON(Object o) {
        String json = new Gson().toJson(o);
        throw new RenderJson(json.getBytes());
    }


   /* Render a specific template
    * @param templateName The template name
    * @param args The template data
    */
    protected static void renderTemplate(String templateName, Object... args) {
        // Template datas
        Map<String, Object> templateBinding = new HashMap<String, Object>(16);
        for (Object o : args) {
            List<String> names = LocalvariablesNamesEnhancer.LocalVariablesNamesTracer.getAllLocalVariableNames(o);
            for (String name : names) {
                templateBinding.put(name, o);
            }
        }
        renderTemplate(templateName, templateBinding);
    }

    /**
     * Render a specific template.
     *
     * @param templateName The template name.
     * @param args         The template data.
     */
    protected static void renderTemplate(String templateName, Map<String, Object> args) {
        // Template datas
        Scope.RenderArgs templateBinding = Scope.RenderArgs.current();
        templateBinding.data.putAll(args);
        templateBinding.put("session", Scope.Session.current());
        templateBinding.put("params", Scope.Params.current());
        templateBinding.put("errors", Validation.errors());
        try {
            Template template = TemplateLoader.load(template(templateName));
            throw new Render(template.render(templateBinding.data).getBytes());
        } catch (TemplateNotFoundException ex) {
            if (ex.isSourceAvailable()) {
                throw ex;
            }
            StackTraceElement element = PlayException.getInterestingStrackTraceElement(ex);
            if (element != null) {
                throw new TemplateNotFoundException(templateName, Play.classes.getApplicationClass(element.getClassName()), element.getLineNumber());
            } else {
                throw ex;
            }
        }
    }

    /**
     * Render the template corresponding to the action's package-class-method name (@see <code>template()</code>).
     *
     * @param args The template data.
     */
    protected static void renderTemplate(Map<String, Object> args) {
        renderTemplate(template(), args);
    }

    /**
     * Render the corresponding template (@see <code>template()</code>).
     *
     * @param args The template data
     */
    protected static void render(Object... args) {
        String templateName = null;
        if (args.length > 0 && args[0] instanceof String && LocalvariablesNamesEnhancer.LocalVariablesNamesTracer.getAllLocalVariableNames(args[0]).isEmpty()) {
            templateName = args[0].toString();
        } else {
            templateName = template();
        }
        renderTemplate(templateName, args);
    }

    /**
     * Work out the default template to load for the invoked action.
     * E.g. "controllers.Pages.index" returns "views/Pages/index.html".
     */
    protected static String template() {
        Inbound inbound = Inbound.current();
        String templateName = inbound.action.replace(".", "/") + ".html";
        if (templateName.startsWith("@")) {
            templateName = templateName.substring(1);
            if (!templateName.contains(".")) {
                templateName = inbound.controller + "." + templateName;
            }
            templateName = templateName.replace(".", "/") + ".html";
        }
        return templateName;
    }

    /**
     * Work out the default template to load for the action.
     * E.g. "controllers.Pages.index" returns "views/Pages/index.html".
     */
    protected static String template(String templateName) {
        Inbound inbound = Inbound.current();
        if (templateName.startsWith("@")) {
            templateName = templateName.substring(1);
            if (!templateName.contains(".")) {
                templateName = inbound.controller + "." + templateName;
            }
            templateName = templateName.replace(".", "/") + ".html";
        }
        return templateName;
    }


    // TODO: Move somewhere else
    public static Object[] getActionMethod(String fullAction) {
        Method actionMethod = null;
        Class controllerClass = null;
        String fAction = fullAction;
        try {
            if (!fullAction.startsWith("notifiers.")) {
                fAction = "notifiers." + fullAction;
            }
            String controller = fAction.substring(0, fAction.lastIndexOf("."));
            String action = fAction.substring(fAction.lastIndexOf(".") + 1);
            controllerClass = Play.classloader.getClassIgnoreCase(controller);

            // TODO: Not sure we want controller after all
            if (controllerClass == null && !fullAction.startsWith("controllers.")) {
                fAction = "controllers." + fullAction;
                controller = fAction.substring(0, fAction.lastIndexOf("."));
                action = fAction.substring(fAction.lastIndexOf(".") + 1);
                controllerClass = Play.classloader.getClassIgnoreCase(controller);
            }
            if (controllerClass == null) {
                throw new ActionNotFoundException(fAction, new Exception("Notifier " + controller + " not found"));
            }

            actionMethod = Java.findActionMethod(action, controllerClass);
            if (actionMethod == null) {
                throw new ActionNotFoundException(fAction, new Exception("No method public static void " + action + "() was found in class " + controller));
            }
        } catch (PlayException e) {
            throw e;
        } catch (Exception e) {
            throw new ActionNotFoundException(fAction, e);
        }
        return new Object[]{controllerClass, actionMethod};
    }


}
