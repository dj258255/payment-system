package com.beomsu.pay.assist.narrative;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface NarrativePreferenceRepository extends JpaRepository<NarrativePreference, Long> {

    List<NarrativePreference> findByChoiceIsNotNull();
}
