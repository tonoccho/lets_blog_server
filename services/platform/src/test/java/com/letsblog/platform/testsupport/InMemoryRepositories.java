package com.letsblog.platform.testsupport;

import com.letsblog.platform.domain.PluginKind;
import com.letsblog.platform.domain.PluginRegistration;
import com.letsblog.platform.domain.ProjectPluginRegistration;
import com.letsblog.platform.domain.SystemSetting;
import com.letsblog.platform.repository.PluginRegistrationRepository;
import com.letsblog.platform.repository.ProjectPluginRegistrationRepository;
import com.letsblog.platform.repository.SystemSettingRepository;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import org.mockito.Mockito;

/**
 * DB(Spring Dataのリポジトリ。自前のコードを持たないインタフェース)の差し替え(issue #1825)。
 * リポジトリは外部との通信なのでMockitoで模してよい境界で、ここでは呼び出しを覚える
 * 小さなメモリ上の実装にして、サービスの実物が保存したものを読み戻せるようにする。
 */
public final class InMemoryRepositories {

    private InMemoryRepositories() {
    }

    /** {@code system_settings}の代わり。findById/findAll/save/deleteById/existsByIdが保存内容を反映する。 */
    public static SystemSettingRepository systemSettings(Map<String, SystemSetting> store) {
        SystemSettingRepository repository = Mockito.mock(SystemSettingRepository.class, Mockito.withSettings().strictness(org.mockito.quality.Strictness.LENIENT));
        Mockito.when(repository.findById(Mockito.anyString()))
                .thenAnswer(inv -> Optional.ofNullable(store.get(inv.<String>getArgument(0))));
        Mockito.when(repository.save(Mockito.any(SystemSetting.class))).thenAnswer(inv -> {
            SystemSetting setting = inv.getArgument(0);
            store.put(setting.getSettingKey(), setting);
            return setting;
        });
        Mockito.doAnswer(inv -> {
            store.remove(inv.<String>getArgument(0));
            return null;
        }).when(repository).deleteById(Mockito.anyString());
        Mockito.when(repository.findAll()).thenAnswer(inv -> List.copyOf(store.values()));
        return repository;
    }

    public static SystemSettingRepository systemSettings() {
        return systemSettings(new ConcurrentHashMap<>());
    }

    /** {@code plugin_registrations}の代わり。派生クエリの意味(並び順・件数・使用中の1件)をメモリ上で再現する。 */
    public static PluginRegistrationRepository pluginRegistrations(List<PluginRegistration> store) {
        PluginRegistrationRepository repository = Mockito.mock(PluginRegistrationRepository.class,
                Mockito.withSettings().strictness(org.mockito.quality.Strictness.LENIENT));
        AtomicLong ids = new AtomicLong(1);
        Comparator<PluginRegistration> byKindThenId = Comparator
                .comparing((PluginRegistration r) -> r.getKind()).thenComparing(PluginRegistration::getId);
        Mockito.when(repository.save(Mockito.any(PluginRegistration.class))).thenAnswer(inv -> {
            PluginRegistration registration = inv.getArgument(0);
            if (registration.getId() == null) {
                registration.setId(ids.getAndIncrement());
                store.add(registration);
            }
            return registration;
        });
        Mockito.when(repository.findById(Mockito.anyLong())).thenAnswer(inv -> store.stream()
                .filter(r -> r.getId().equals(inv.<Long>getArgument(0))).findFirst());
        Mockito.doAnswer(inv -> store.remove(inv.<PluginRegistration>getArgument(0))).when(repository)
                .delete(Mockito.any(PluginRegistration.class));
        Mockito.when(repository.findAllByOrderByKindAscIdAsc()).thenAnswer(inv -> {
            List<PluginRegistration> sorted = new ArrayList<>(store);
            sorted.sort(byKindThenId);
            return sorted;
        });
        Mockito.when(repository.findByKindOrderByIdAsc(Mockito.any(PluginKind.class))).thenAnswer(inv -> store.stream()
                .filter(r -> r.getKind() == inv.<PluginKind>getArgument(0))
                .sorted(Comparator.comparing(PluginRegistration::getId)).toList());
        Mockito.when(repository.countByKind(Mockito.any(PluginKind.class))).thenAnswer(inv -> store.stream()
                .filter(r -> r.getKind() == inv.<PluginKind>getArgument(0)).count());
        Mockito.when(repository.findFirstByKindAndInUseTrue(Mockito.any(PluginKind.class))).thenAnswer(inv -> store.stream()
                .filter(r -> r.getKind() == inv.<PluginKind>getArgument(0) && r.isInUse()).findFirst());
        return repository;
    }

    /** {@code project_plugin_registrations}の代わり。 */
    public static ProjectPluginRegistrationRepository projectPluginRegistrations(List<ProjectPluginRegistration> store) {
        ProjectPluginRegistrationRepository repository = Mockito.mock(ProjectPluginRegistrationRepository.class,
                Mockito.withSettings().strictness(org.mockito.quality.Strictness.LENIENT));
        AtomicLong ids = new AtomicLong(1);
        Mockito.when(repository.save(Mockito.any(ProjectPluginRegistration.class))).thenAnswer(inv -> {
            ProjectPluginRegistration registration = inv.getArgument(0);
            if (registration.getId() == null) {
                registration.setId(ids.getAndIncrement());
                store.add(registration);
            }
            return registration;
        });
        Mockito.doAnswer(inv -> store.remove(inv.<ProjectPluginRegistration>getArgument(0))).when(repository)
                .delete(Mockito.any(ProjectPluginRegistration.class));
        Mockito.when(repository.findByIdAndProjectId(Mockito.anyLong(), Mockito.anyLong())).thenAnswer(inv -> store.stream()
                .filter(r -> r.getId().equals(inv.<Long>getArgument(0)) && r.getProjectId().equals(inv.<Long>getArgument(1)))
                .findFirst());
        Mockito.when(repository.findByProjectIdOrderByKindAscIdAsc(Mockito.anyLong())).thenAnswer(inv -> store.stream()
                .filter(r -> r.getProjectId().equals(inv.<Long>getArgument(0)))
                .sorted(Comparator.comparing((ProjectPluginRegistration r) -> r.getKind())
                        .thenComparing(ProjectPluginRegistration::getId)).toList());
        Mockito.when(repository.findByProjectIdAndKindOrderByIdAsc(Mockito.anyLong(), Mockito.any(PluginKind.class)))
                .thenAnswer(inv -> store.stream()
                        .filter(r -> r.getProjectId().equals(inv.<Long>getArgument(0))
                                && r.getKind() == inv.<PluginKind>getArgument(1))
                        .sorted(Comparator.comparing(ProjectPluginRegistration::getId)).toList());
        Mockito.when(repository.countByProjectIdAndKind(Mockito.anyLong(), Mockito.any(PluginKind.class)))
                .thenAnswer(inv -> store.stream()
                        .filter(r -> r.getProjectId().equals(inv.<Long>getArgument(0))
                                && r.getKind() == inv.<PluginKind>getArgument(1)).count());
        Mockito.when(repository.findFirstByProjectIdAndKindAndInUseTrue(Mockito.anyLong(), Mockito.any(PluginKind.class)))
                .thenAnswer(inv -> store.stream()
                        .filter(r -> r.getProjectId().equals(inv.<Long>getArgument(0))
                                && r.getKind() == inv.<PluginKind>getArgument(1) && r.isInUse())
                        .findFirst());
        return repository;
    }
}
