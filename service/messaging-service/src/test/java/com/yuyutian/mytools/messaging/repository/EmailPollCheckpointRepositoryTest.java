package com.yuyutian.mytools.messaging.repository;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class EmailPollCheckpointRepositoryTest {
    @Autowired
    private EmailPollCheckpointRepository repository;

    @Test
    void shouldAdvanceUidAndResetAfterUidValidityChanges() {
        repository.save("primary_email", "INBOX", 7, 9);
        repository.save("primary_email", "INBOX", 7, 8);

        assertThat(repository.find("primary_email", "INBOX").orElseThrow().lastUid()).isEqualTo(9);

        repository.save("primary_email", "INBOX", 8, 2);

        EmailPollCheckpointRepository.Checkpoint checkpoint = repository.find("primary_email", "INBOX")
                .orElseThrow();
        assertThat(checkpoint.uidValidity()).isEqualTo(8);
        assertThat(checkpoint.lastUid()).isEqualTo(2);
    }
}
