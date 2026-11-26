package com.yuyutian.mytools.messaging.service;

import com.yuyutian.mytools.messaging.config.OutboundProperties;
import com.yuyutian.mytools.messaging.model.OutboundModels.Content;
import com.yuyutian.mytools.messaging.utils.outbound.OutboundSecurity;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class OutboundSecurityTest {
    @Test void credentialsCannotCrossAdministrativeWorkerAndClientBoundaries() {
        var p=new OutboundProperties();
        var security=new OutboundSecurity(p);
        assertThatThrownBy(() -> security.authorize("Bearer ","client")).isInstanceOf(OutboundException.class);
        p.setClientToken("c".repeat(32)); p.setWorkerToken("w".repeat(32)); p.setAdminToken("a".repeat(32));
        assertThatCode(() -> security.authorize("Bearer "+p.getClientToken(),"client")).doesNotThrowAnyException();
        assertThatThrownBy(() -> security.authorize("Bearer "+p.getWorkerToken(),"admin")).isInstanceOf(OutboundException.class);
        assertThatThrownBy(() -> security.authorize("Bearer "+p.getClientToken(),"worker")).isInstanceOf(OutboundException.class);
        p.setAdminToken(p.getClientToken());
        assertThatThrownBy(() -> security.authorize("Bearer "+p.getClientToken(),"client")).isInstanceOf(OutboundException.class);
    }
    @Test void encryptedValuesUseUniqueNoncesAndRejectTampering() {
        var p=new OutboundProperties(); p.setEncryptionKey("AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=");
        var security=new OutboundSecurity(p);
        String one=security.encrypt("private-recipient", "owner:destination:EMAIL");
        String two=security.encrypt("private-recipient", "owner:destination:EMAIL");
        assertThat(one).isNotEqualTo(two);
        assertThat(security.decrypt(one,"owner:destination:EMAIL")).isEqualTo("private-recipient");
        assertThatThrownBy(() -> security.decrypt(one,"another-owner")).isInstanceOf(OutboundException.class);
        byte[] bytes=java.util.Base64.getDecoder().decode(one); bytes[bytes.length-1]^=1;
        assertThatThrownBy(() -> security.decrypt(java.util.Base64.getEncoder().encodeToString(bytes),"owner:destination:EMAIL")).isInstanceOf(OutboundException.class);
    }
    @Test void framedHashDistinguishesFieldsAndNormalizesOnlyAbsentHtml() {
        assertThat(OutboundSecurity.hash(new Content("ab","c",null))).isNotEqualTo(OutboundSecurity.hash(new Content("a","bc",null)));
        assertThat(OutboundSecurity.hash(new Content("ab","c",null))).isEqualTo(OutboundSecurity.hash(new Content("ab","c","")));
    }
}
