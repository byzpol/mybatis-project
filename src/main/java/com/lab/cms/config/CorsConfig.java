package com.lab.cms.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * 跨域（CORS）配置。
 *
 * <h3>为什么需要它</h3>
 * <p>前端是 Vite 独立开发服务器（默认 {@code http://localhost:5173}），
 * 后端是 Tomcat（{@code http://localhost:8080}）。端口不同 → 浏览器的同源策略拦截。
 *
 * <h3>两道防线（本项目都要保留）</h3>
 * <ol>
 *   <li><b>前端 Vite proxy</b>（{@code vite.config.js} 的 {@code server.proxy}）：
 *       开发期走这个。请求发到 5173，由 Vite 转发到 8080，
 *       对浏览器而言是同源请求，**不带 Cookie 跨域问题**，最省事。</li>
 *   <li><b>后端 CORS（本类）</b>：用于"前端独立部署后直连后端"的场景
 *       （如答辩时用 {@code npm run build} 出的静态包 + Tomcat）。
 *       如果只配 proxy，到部署环节会返工。</li>
 * </ol>
 *
 * <h3>⚠️ allowCredentials(true) 与 allowedOrigins("*") 不能同时用</h3>
 * <p>这是 CORS 规范的限制 + Spring 的实现校验：
 * 当 {@code allowCredentials = true} 时，响应头
 * {@code Access-Control-Allow-Origin} **不允许**是通配符 {@code *}
 * （因为那样等于允许任意站点携带用户 Cookie 访问，是严重的安全漏洞）。
 *
 * <p>Spring 5.3 在启动时若检测到这个组合会直接抛异常：
 * <pre>
 *   When allowCredentials is true, allowedOrigins cannot contain the special value "*"
 *   since that cannot be set on the "Access-Control-Allow-Origin" response header.
 *   To allow credentials to a set of origins, list them explicitly or consider using
 *   "allowedOriginPatterns" instead.
 * </pre>
 *
 * <p>解决方案是用 {@code allowedOriginPatterns}（支持通配符模式匹配，
 * 但会回显具体的 Origin 而不是返回 {@code *}）。
 *
 * <h3>⚠️ 本类与 SpringMvcConfig 的关系</h3>
 * <p>本类也实现了 {@code WebMvcConfigurer}，与 {@code SpringMvcConfig} 是**并列**的
 * 两个配置类。SpringMVC 会**合并**所有 {@code WebMvcConfigurer} 实现的配置
 * （不是"后者覆盖前者"），所以两处的配置都会生效。
 *
 * <p>这样拆分的好处：跨域是"部署相关"的关注点，与 MVC 的"请求处理"关注点分离，
 * 将来要改跨域规则只需看这一个文件。
 */
@Configuration
public class CorsConfig implements WebMvcConfigurer {

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/api/**")
                // ⚠️ 必须用 allowedOriginPatterns（不能是 allowedOrigins("*")），
                //    原因见类注释：allowCredentials(true) 与 "*" 互斥。
                //    只放开 localhost 与 127.0.0.1 的任意端口：
                //    覆盖 Vite 的 5173，也覆盖端口被占用时 Vite 自动切换到 5174/5175 的情况。
                .allowedOriginPatterns(
                        "http://localhost:[*]",
                        "http://127.0.0.1:[*]"
                )
                // 项目实际用到的方法（不含 PATCH —— 本项目状态变更全用 POST 子资源）
                .allowedMethods("GET", "POST", "PUT", "DELETE", "OPTIONS")
                // 允许所有请求头（前端会带 Content-Type；将来加自定义 token 头也不用改）
                .allowedHeaders("*")
                // 允许携带 Cookie（Session 靠 JSESSIONID Cookie 传递，必须为 true）
                .allowCredentials(true)
                // 预检请求的缓存时间（秒）：1 小时内不再重复发预检，减少请求数
                .maxAge(3600);
    }
}
