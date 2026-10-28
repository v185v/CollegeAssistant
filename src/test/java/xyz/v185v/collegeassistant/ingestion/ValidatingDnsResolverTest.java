package xyz.v185v.collegeassistant.ingestion;

import xyz.v185v.collegeassistant.ingestion.crawl.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.InetAddress;
import java.net.Inet6Address;
import java.net.UnknownHostException;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class ValidatingDnsResolverTest {

    @Test
    void pinsFirstValidatedSnapshotAndNeverConsultsDelegateTwice() throws Exception {
        InetAddress publicAddress = InetAddress.getByAddress(new byte[]{8, 8, 8, 8});
        InetAddress loopback = InetAddress.getByAddress(new byte[]{127, 0, 0, 1});
        AtomicInteger calls = new AtomicInteger();
        HostAddressResolver scripted = host -> calls.getAndIncrement() == 0
                ? new InetAddress[]{publicAddress}
                : new InetAddress[]{loopback};
        ValidatingDnsResolver firstRequest = new ValidatingDnsResolver(
                "source.example", scripted);

        InetAddress[] first = firstRequest.resolve("source.example");
        first[0] = loopback;
        InetAddress[] second = firstRequest.resolve("SOURCE.EXAMPLE");

        assertThat(second).containsExactly(publicAddress);
        assertThat(second).isNotSameAs(first);
        assertThat(calls).hasValue(1);

        ValidatingDnsResolver nextRequest = new ValidatingDnsResolver(
                "source.example", scripted);
        assertThatThrownBy(() -> nextRequest.resolve("source.example"))
                .isInstanceOf(UnknownHostException.class);
        assertThat(calls).hasValue(2);
    }

    @Test
    void rejectsUnexpectedHostnameWithoutResolvingIt() {
        AtomicInteger calls = new AtomicInteger();
        ValidatingDnsResolver resolver = new ValidatingDnsResolver(
                "source.example",
                host -> {
                    calls.incrementAndGet();
                    return new InetAddress[]{InetAddress.getByName("8.8.8.8")};
                });

        assertThatThrownBy(() -> resolver.resolve("attacker.example"))
                .isInstanceOf(UnknownHostException.class);
        assertThat(calls).hasValue(0);
    }

    @Test
    void rejectsMixedPublicAndNonPublicResolution() throws Exception {
        ValidatingDnsResolver resolver = new ValidatingDnsResolver(
                "source.example",
                host -> new InetAddress[]{
                        InetAddress.getByName("8.8.8.8"),
                        InetAddress.getByName("10.0.0.1")});

        assertThatThrownBy(() -> resolver.resolve("source.example"))
                .isInstanceOf(UnknownHostException.class);
    }

    @Test
    void rejectsPrivateIpv4AddressForcedIntoIpv6MappedForm() throws Exception {
        byte[] mapped = new byte[16];
        mapped[10] = (byte) 0xff;
        mapped[11] = (byte) 0xff;
        mapped[12] = 10;
        mapped[15] = 1;
        Inet6Address mappedPrivate = Inet6Address.getByAddress(null, mapped, -1);

        assertThatThrownBy(() -> HostAddressPolicy.requirePublic(mappedPrivate))
                .isInstanceOf(java.io.IOException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "0.1.2.3",
            "10.0.0.1",
            "100.64.0.1",
            "127.0.0.1",
            "169.254.1.1",
            "172.16.0.1",
            "192.0.2.1",
            "192.168.0.1",
            "198.18.0.1",
            "198.51.100.1",
            "203.0.113.1",
            "224.0.0.1",
            "240.0.0.1",
            "::1",
            "::ffff:10.0.0.1",
            "64:ff9b::1",
            "100::1",
            "2001:db8::1",
            "2002::1",
            "3fff::1",
            "fc00::1",
            "fe80::1",
            "ff02::1"
    })
    void rejectsSpecialUseAddresses(String literal) throws Exception {
        InetAddress address = InetAddress.getByName(literal);

        assertThatThrownBy(() -> HostAddressPolicy.requirePublic(address))
                .isInstanceOf(java.io.IOException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"8.8.8.8", "1.1.1.1", "2001:4860:4860::8888"})
    void acceptsPublicAddresses(String literal) throws Exception {
        HostAddressPolicy.requirePublic(InetAddress.getByName(literal));
    }
}
