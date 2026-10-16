package com.letsblog.common.net;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.InetAddress;
import org.junit.jupiter.api.Test;

/** IPv4射影アドレスの判定(#1831)。射影とみなす条件の各項を、1つずつ外した入力で確かめる。 */
class DestinationAddressRulesTest {

    private static byte[] bytes(String literal) throws Exception {
        return InetAddress.getByName(literal).getAddress();
    }

    @Test
    void ipv4MappedAddressIsUnwrappedToTheLastFourBytes() throws Exception {
        byte[] mapped = new byte[16];
        mapped[10] = (byte) 0xff;
        mapped[11] = (byte) 0xff;
        mapped[12] = 10;
        mapped[15] = 5;

        assertThat(DestinationAddressRules.unmapIpv4(mapped)).containsExactly(10, 0, 0, 5);
    }

    @Test
    void anIpv4AddressIsReturnedAsIs() throws Exception {
        byte[] v4 = bytes("192.168.0.1");

        assertThat(DestinationAddressRules.unmapIpv4(v4)).isSameAs(v4);
    }

    @Test
    void anIpv6AddressWithANonZeroPrefixIsReturnedAsIs() throws Exception {
        byte[] v6 = bytes("2001:db8::ffff:1");

        assertThat(DestinationAddressRules.unmapIpv4(v6)).isSameAs(v6);
    }

    @Test
    void anIpv6AddressWhoseByte10IsNotFfIsReturnedAsIs() throws Exception {
        byte[] v6 = new byte[16];
        v6[11] = (byte) 0xff;

        assertThat(DestinationAddressRules.unmapIpv4(v6)).isSameAs(v6);
    }

    @Test
    void anIpv6AddressWhoseByte11IsNotFfIsReturnedAsIs() throws Exception {
        byte[] v6 = new byte[16];
        v6[10] = (byte) 0xff;

        assertThat(DestinationAddressRules.unmapIpv4(v6)).isSameAs(v6);
    }

    @Test
    void ec2MetadataIpv6AddressIsDeniedAndNeighboursAreNot() throws Exception {
        assertThat(DestinationAddressRules.isDeniedAddress(bytes("fd00:ec2::254"))).isTrue();
        assertThat(DestinationAddressRules.isDeniedAddress(bytes("fd00:ec2::255"))).isFalse();
        assertThat(DestinationAddressRules.isDeniedAddress(bytes("fd00:ec2::1:254"))).isFalse();
        assertThat(DestinationAddressRules.isDeniedAddress(bytes("fd01:ec2::254"))).isFalse();
        assertThat(DestinationAddressRules.isDeniedAddress(bytes("fd00:ec3::254"))).isFalse();
        assertThat(DestinationAddressRules.isDeniedAddress(bytes("fd00:ec2:0:1::254"))).isFalse();
    }
}
