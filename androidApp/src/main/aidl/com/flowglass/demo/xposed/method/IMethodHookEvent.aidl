package com.flowglass.demo.xposed.method;

/** One signed target-process packet. The App validates caller UID before parsing the body. */
interface IMethodHookEvent {
    boolean send(String packet);
}
