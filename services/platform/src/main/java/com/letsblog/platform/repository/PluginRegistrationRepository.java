package com.letsblog.platform.repository;

import com.letsblog.platform.domain.PluginKind;
import com.letsblog.platform.domain.PluginRegistration;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface PluginRegistrationRepository extends JpaRepository<PluginRegistration, Long> {

    List<PluginRegistration> findAllByOrderByKindAscIdAsc();

    List<PluginRegistration> findByKindOrderByIdAsc(PluginKind kind);

    long countByKind(PluginKind kind);

    Optional<PluginRegistration> findFirstByKindAndInUseTrue(PluginKind kind);
}
