package com.oshare.airdrop;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.net.Socket;
import org.junit.Test;

/**
 * 接收侧失败分类的判据（P1-24）。这些判据全部来自真机日志：
 * {@code routine} 的 SocketException 家族、{@code aborted} 的 ECONNABORTED 方向、
 * {@code readable} 的五个 Rust 错误短语、{@link Explains#bytes} 的单位阶梯。
 */
public class ExplainsTest {

    @Test
    public void bytes_walks_the_unit_ladder() {
        assertEquals("1023 B", Explains.bytes(1023));
        assertEquals("1.0 KiB", Explains.bytes(1024));
        assertEquals("1.5 KiB", Explains.bytes(1536));
        assertEquals("1.0 MiB", Explains.bytes(1024 * 1024));
        assertEquals("88.7 MiB", Explains.bytes(93_051_139));  // 日志里那个 93MB 文件
        // 1 GiB 之后还有 TiB 兜底；超出梯子就原样退回 B 形式。
        assertEquals("1.0 GiB", Explains.bytes(1024L * 1024 * 1024));
        assertEquals("0 B", Explains.bytes(0));
    }

    @Test
    public void socket_exceptions_are_routine_except_abort() throws Exception {
        assertTrue(Explains.routine(new Exception("Broken pipe",
                new java.net.SocketException("Connection reset"))));
        assertTrue(Explains.routine(new Exception("x",
                new java.net.SocketException("Socket closed"))));
        // ECONNABORTED 是本机内核中止，不是对端走掉——2026-11-17 的后台限网现场。
        assertFalse(Explains.routine(new Exception("x",
                new java.net.SocketException("Software caused connection abort"))));
        // 无关异常（TLS 失败等）不是例行事件。
        assertFalse(Explains.routine(new java.io.IOException("handshake failed")));
    }

    @Test
    public void aborted_only_matches_the_errno_text() throws Exception {
        assertTrue(Explains.aborted(new java.net.SocketException("Software caused connection abort")));
        assertFalse(Explains.aborted(new java.net.SocketException("Connection reset")));
        assertFalse(Explains.aborted(new java.io.IOException("broken pipe")));
    }

    @Test
    public void because_splits_on_landed_bytes() {
        // 一个字节都没落地：只有本机这边先没了（限网、息屏），说可行动的那一步。
        assertEquals("连接被系统切断，检查后台限网",
                Explains.because(new Exception("x",
                        new java.net.SocketException("Software caused connection abort")), 0));
        // 传到一半断的：谁停的从这侧分不出来，只说发生了什么、丢了多少。
        assertEquals("传输在中途被中断，已收到 2.3 MiB，没有存下",
                Explains.because(new Exception("x",
                        new java.net.SocketException("Software caused connection abort")), 2_411_724));
    }

    @Test
    public void readable_translates_the_five_rust_errors() {
        assertEquals("对面发来的文件包读不出来，让他再发一次",
                Explains.readable(new java.io.IOException("the archive could not be read: bad magic")));
        assertEquals("文件没能写到存储上，检查空间够不够",
                Explains.readable(new java.io.IOException("the inbox could not be written")));
        assertEquals("一次传的超过了单次接收上限，没有存下",
                Explains.readable(new java.io.IOException("payload exceeds the inbox budget of 2 GiB")));
        assertEquals("同名文件太多，另起一个名字也存不下",
                Explains.readable(new java.io.IOException("no free name for IMG_0001.jpg")));
        assertEquals("这一份的文件名不安全，没有存下",
                Explains.readable(new java.io.IOException("../resolves outside the inbox")));
        // 兜底：认不出原文就给通用的一句。
        assertEquals("连接在中途出了问题，这一份没有存下",
                Explains.readable(new java.io.IOException("something else entirely")));
    }

    @Test
    public void describe_prefers_the_message() {
        assertEquals("boom", Explains.describe(new RuntimeException("boom")));
        assertEquals("java.lang.RuntimeException", Explains.describe(new RuntimeException()));
    }

    @Test
    public void where_of_a_socket_without_a_peer_is_unknown() {
        // 从没连出去过的 socket 没有对端地址——日志里要写 "?" 而不是抛异常。
        assertEquals("?", Explains.where(new Socket()));
    }
}
