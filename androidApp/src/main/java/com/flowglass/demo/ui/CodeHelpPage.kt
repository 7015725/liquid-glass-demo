package com.flowglass.demo.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kyant.shapes.RoundedRectangle

/** Full-page reference for the three code actions. */
@Composable
internal fun CodeHelpPage(typeId: String, onBack: () -> Unit) {
    val title = when (typeId) {
        "action.script" -> "JavaScript 帮助"
        "action.root_shell" -> "Shell 帮助"
        "action.java_code" -> "Java 帮助"
        else -> "代码帮助"
    }
    Column(
        Modifier.fillMaxSize().background(C.Bg).statusBarsPadding().navigationBarsPadding(),
    ) {
        Row(
            Modifier.fillMaxWidth().height(TopBarControlTouch).padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TopBarIconButton(Ic.Back, "返回", onClick = onBack)
            Spacer(Modifier.weight(1f))
            T(title, 18.sp, FontWeight.Bold, maxLines = 1)
            Spacer(Modifier.weight(1f))
        }
        Column(
            Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            when (typeId) {
                "action.script" -> JavaScriptHelp()
                "action.root_shell" -> ShellHelp()
                "action.java_code" -> JavaHelp()
                else -> HelpBody("暂无该代码动作的说明。")
            }
            Spacer(Modifier.height(12.dp))
        }
    }
}

@Composable
private fun JavaScriptHelp() {
    HelpSection("执行环境") {
        HelpBody("Rhino 1.9.2，ES6 语法，解释执行；脚本在应用进程内运行，没有独立虚拟机。")
        HelpBody("当前为无沙箱模式：可以访问 Java / Android 类，脚本以本应用权限运行。")
        HelpBody("同步执行，不支持 Promise / async 等待，也没有 DOM；网络请求请用 http()。")
    }
    HelpSection("语法") {
        HelpBody("支持 var / let / const、函数、对象、数组、模板字符串等 ES6 常用语法。")
        HelpBody("脚本没有顶层 return；最后一个表达式的值会作为脚本返回值。")
        HelpBody("Android 对象通过 Rhino 的 Java 桥调用，方法名和参数与 Java 一致。")
    }
    HelpSection("全局 API") {
        HelpCode(
            """
            get(name) / set(name, value)
            log(text)
            toast(text)
            notify(title, text)
            http(method, url, body)
            sleep(millis)
            context
            Packages.*
            Java.type("类名")
            """.trimIndent(),
        )
        HelpBody("http 支持 GET / POST；sleep 最多 5000 ms；Java.type 的参数是类名字符串。")
        HelpBody("get / set 的名称支持裸名，以及 rule. / global. / run. / event. 前缀。")
    }
    HelpSection("变量") {
        HelpBullet("{变量} 是只读实时绑定：每次读取都取当前值；set() 后再次读取能看到新值。")
        HelpBullet("普通文本里的 {变量} 会在执行前展开；模板字符串中只展开 ${'$'}{...} 内部的 {变量}。")
        HelpBullet("脚本返回值写入 script.result；运行日志用 log() 输出。")
    }
    HelpSection("示例") {
        HelpCode(
            """
            var pm = context.getPackageManager();
            var pkg = context.getPackageName();
            var info = pm.getPackageInfo(pkg, 0);
            set("版本", info.versionName);
            log("版本：" + info.versionName);
            info.versionName;
            """.trimIndent(),
        )
    }
    HelpSection("超时与安全") {
        HelpBody("默认 10 秒，范围 1–60 秒；超时后动作失败。阻塞式 SDK 或网络调用可能无法被强制中断。")
        HelpBody("无沙箱：脚本可读写文件、访问网络并调用公开 SDK；部分 API 仍受应用权限、前台状态和 Android 隐藏 API 限制。")
    }
}

@Composable
private fun ShellHelp() {
    HelpSection("执行环境") {
        HelpBody("通过 Root 会话执行 POSIX Shell 命令；需要先授予 Root 权限。")
        HelpBody("固定 30 秒超时；标准输出、标准错误和退出码会分别采集，退出码 0 视为成功。")
    }
    HelpSection("语法") {
        HelpBody("可以写单条命令，也可以写多行 Shell 脚本；按 Shell 语法解析，支持管道、重定向和常见控制结构。")
        HelpBody("命令中的 {变量} 会先写入生成的 FG_V1、FG_V2… 变量，再以 ${'$'}{FG_V1} 形式引用；不需要手动加引号。")
    }
    HelpSection("变量") {
        HelpBullet("直接写 {变量} 即可；值不会拼进命令文本，避免引号或空格改变命令结构。")
        HelpBullet("在双引号、单引号和 heredoc 中都可以使用 {变量}，展开后仍然是安全的引用。")
        HelpBullet("变量名会按出现顺序分配 FG_V1、FG_V2…；不要依赖这个编号，按名称书写即可。")
    }
    HelpSection("输出") {
        HelpCode(
            """
            shell.stdout  标准输出
            shell.stderr  标准错误
            shell.code    退出码（超时或输出超限为 -1）
            """.trimIndent(),
        )
    }
    HelpSection("示例") {
        HelpCode(
            """
            id
            echo "型号：{设备型号}"
            pm list packages | grep flowglass
            """.trimIndent(),
        )
    }
    HelpSection("安全") {
        HelpBody("命令以 Root 身份运行，可能修改系统设置或删除文件；只运行你理解并且信任的命令。")
    }
}

@Composable
private fun JavaHelp() {
    HelpSection("执行环境") {
        HelpBody("BeanShell 解释执行，语法接近 Java 5；不支持泛型、Lambda、Stream 等较新的语法。")
        HelpBody("代码在应用进程内运行，没有沙箱，以本应用权限访问 Java 与 Android SDK。")
    }
    HelpSection("对象与 API") {
        HelpCode(
            """
            context
            host.getVariable("名字")
            host.setVariable("名字", value)
            host.clearVariable("名字")
            host.log(message)
            host.showToast(text)
            host.notify(title, text)
            host.http(method, url, body)
            host.sleep(millis)
            host.getTaskName()
            host.getTaskId()
            """.trimIndent(),
        )
        HelpBody("http 支持 GET / POST；sleep 最多 5000 ms；getTaskName / getTaskId 返回当前动作信息。")
    }
    HelpSection("变量") {
        HelpBullet("读取变量写 host.getVariable(\"名字\")；写入用 host.setVariable。")
        HelpBullet("Java 代码里不要写 {名字}，它不会被当作参数模板展开。")
        HelpBullet("返回值用 return；结果写入 java.result。")
    }
    HelpSection("示例") {
        HelpCode(
            """
            var pm = context.getPackageManager();
            var pkg = context.getPackageName();
            var info = pm.getPackageInfo(pkg, 0);
            host.setVariable("版本", info.versionName);
            host.log("版本：" + info.versionName);
            return info.versionName;
            """.trimIndent(),
        )
    }
    HelpSection("超时与安全") {
        HelpBody("默认 30 秒，范围 1–120 秒。超时后动作失败，工作线程会被放弃，死循环无法强制终止。")
        HelpBody("无沙箱：代码可读写文件、访问网络并调用公开 SDK；部分 API 仍受应用权限、前台状态和 Android 隐藏 API 限制。")
    }
}

@Composable
private fun HelpSection(title: String, body: @Composable ColumnScope.() -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        T(title, 15.sp, FontWeight.SemiBold)
        body()
    }
}

@Composable
private fun HelpBody(text: String) {
    T(text, 13.sp, color = C.OnSurfaceVar, lineHeight = 20.sp)
}

@Composable
private fun HelpBullet(text: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        T("•", 13.sp, color = C.Primary, lineHeight = 20.sp)
        T(text, 13.sp, color = C.OnSurfaceVar, lineHeight = 20.sp)
    }
}

@Composable
private fun HelpCode(code: String) {
    BasicText(
        text = code,
        style = TextStyle(
            color = C.OnSurface,
            fontSize = 12.sp,
            fontFamily = FontFamily.Monospace,
            lineHeight = 19.sp,
        ),
        modifier = Modifier.fillMaxWidth()
            .clip(RoundedRectangle(12.dp))
            .background(C.Card)
            .padding(horizontal = 12.dp, vertical = 10.dp),
    )
}
