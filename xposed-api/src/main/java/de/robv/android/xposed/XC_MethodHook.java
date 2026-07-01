package de.robv.android.xposed;

import java.lang.reflect.Member;

/**
 * 方法Hook回调
 */
public abstract class XC_MethodHook {
    /**
     * 方法Hook参数
     */
    public static final class MethodHookParam {
        public Member method;
        public Object thisObject;
        public Object[] args;
        private Object result;
        private Throwable throwable;
        private boolean resultOrThrowableHandled = false;
        public boolean returnEarly = false;

        public Object getResult() {
            return result;
        }
        public Throwable getThrowable() {
            return throwable;
        }
        public void setResult(Object result) {
            this.result = result;
            this.resultOrThrowableHandled = true;
            this.throwable = null;
        }
        public void setThrowable(Throwable throwable) {
            this.throwable = throwable;
            this.resultOrThrowableHandled = true;
            this.result = null;
        }
        public Object getResultOrThrowable() throws Throwable {
            if (throwable != null) throw throwable;
            return result;
        }
    }

    /**
     * 取消Hook接口
     */
    public interface Unhook {
        void unhook();
    }

    protected void beforeHookedMethod(MethodHookParam param) throws Throwable {}
    protected void afterHookedMethod(MethodHookParam param) throws Throwable {}
}
