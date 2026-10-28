package xyz.v185v.collegeassistant.ingestion.crawl;

import java.io.IOException;
import java.net.InetAddress;

/** 可替换的主机名解析器，便于隔离 DNS 解析并进行测试。 */
@FunctionalInterface
public interface HostAddressResolver {

    InetAddress[] resolve(String host) throws IOException;
}
