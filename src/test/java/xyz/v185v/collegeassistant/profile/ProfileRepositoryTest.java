package xyz.v185v.collegeassistant.profile;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

import tools.jackson.databind.json.JsonMapper;

class ProfileRepositoryTest {

    private final ProfileRepository profileRepository = new ProfileRepository(
            null,
            JsonMapper.builder().build());

    @Test
    void deserializeRejectsNullElementsWithExplicitException() {
        assertThatThrownBy(() -> profileRepository.deserializeCourseRecords("[null]"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Profile course records must not contain null elements");
    }

    @Test
    void deserializeReturnsEmptyListForMissingOrEmptyJson() {
        assertThat(profileRepository.deserializeCourseRecords(null)).isEmpty();
        assertThat(profileRepository.deserializeCourseRecords(" ")).isEmpty();
        assertThat(profileRepository.deserializeCourseRecords("[]")).isEmpty();
    }
}
