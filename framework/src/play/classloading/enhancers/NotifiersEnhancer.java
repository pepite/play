package play.classloading.enhancers;

import javassist.*;
import javassist.bytecode.annotation.Annotation;
import javassist.expr.ExprEditor;
import javassist.expr.FieldAccess;
import javassist.expr.Handler;
import play.Logger;
import play.Play;
import play.classloading.ApplicationClasses.ApplicationClass;
import play.exceptions.UnexpectedException;
import play.mvc.Notifier;
import play.notifiers.Render;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Enhance controllers classes.
 */
public class NotifiersEnhancer extends Enhancer {

    @Override
    public void enhanceThisClass(final ApplicationClass applicationClass) throws Exception {
        CtClass ctClass = makeClass(applicationClass);

        if (!ctClass.subtypeOf(classPool.get(Notifier.class.getName()))) {
            return;
        }


        for (final CtMethod ctMethod : ctClass.getDeclaredMethods()) {

            // Threaded access		
            ctMethod.instrument(new ExprEditor() {

                @Override
                public void edit(FieldAccess fieldAccess) throws CannotCompileException {
                    try {
                        if (isThreadedFieldAccess(fieldAccess.getField())) {
                            if (fieldAccess.isReader()) {
                                fieldAccess.replace("$_ = ($r)play.utils.Java.invokeStatic($type, \"current\");");
                            }
                        }
                    } catch (Exception e) {
                        Logger.error(e, "Error in ControllersEnhancer. %s.%s has not been properly enhanced (fieldAccess %s).", applicationClass.name, ctMethod.getName(), fieldAccess);
                        throw new UnexpectedException(e);
                    }
                }
            });


            if (Modifier.isPublic(ctMethod.getModifiers()) && (Modifier.isStatic(ctMethod.getModifiers()) && ctMethod.getReturnType().equals(CtClass.voidType))) {
                try {

                    ctMethod.addCatch(" if(!play.classloading.enhancers.NotifiersEnhancer.NotifierInstrumentation.isActionCallAllowed())  { " +
                            "System.err.println(\"" + ctClass.getSimpleName() + "." + ctMethod.getName() + "\");" +
                            "play.mvc.Router.ActionDefinition a = play.mvc.Router.reverse(\"" + ctClass.getSimpleName() + "." + ctMethod.getName() + "\");\n" +
                            " play.server.websocket.WebSocketServerHandler.write(a.url, $e.content); } else { throw $e; } return;", classPool.get(Render.class.getName()), "$e");
                } catch (Exception e) {
                    Logger.error(e, "Error in NotifiersEnhancer. %s.%s has not been properly enhanced.", applicationClass.name, ctMethod.getName());
                    throw new UnexpectedException(e);
                }
            }


        }

        // Done.
        applicationClass.enhancedByteCode = ctClass.toBytecode();
        ctClass.defrost();

    }

    /**
     * Mark class that need controller enhancement
     */
    public static interface NotifierSupport {
    }

    /**
     * Check if a field must be translated to a 'thread safe field'
     */
    static boolean isThreadedFieldAccess(CtField field) {
        if (field.getDeclaringClass().getName().equals("play.mvc.Notifier")) {
            return field.getName().equals("inbound")
                    || field.getName().equals("outbound")
                    || field.getName().equals("renderArgs");
        }
        return false;
    }

    /**
     * Runtime part needed by the instrumentation
     */
    public static class NotifierInstrumentation {

        public static boolean isActionCallAllowed() {
            return allow.get() != null ? allow.get() : false;
        }

        public static void initActionCall() {
            allow.set(true);
        }

        public static void stopActionCall() {
            allow.set(false);
        }

        static ThreadLocal<Boolean> allow = new ThreadLocal<Boolean>();
    }

}
