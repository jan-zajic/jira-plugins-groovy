package ru.mail.jira.plugins.groovy.impl.repository;

import com.atlassian.activeobjects.external.ActiveObjects;
import com.atlassian.cache.Cache;
import com.atlassian.cache.CacheLoader;
import com.atlassian.cache.CacheManager;
import com.atlassian.cache.CacheSettingsBuilder;
import com.atlassian.jira.user.ApplicationUser;
import com.atlassian.jira.util.I18nHelper;
import com.atlassian.plugin.spring.scanner.annotation.export.ExportAsDevService;
import com.atlassian.plugin.spring.scanner.annotation.imports.ComponentImport;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import net.java.ao.Query;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import ru.mail.jira.plugins.groovy.api.dao.ListenerDao;
import ru.mail.jira.plugins.groovy.api.entity.*;
import ru.mail.jira.plugins.groovy.util.*;
import ru.mail.jira.plugins.groovy.api.repository.EventListenerRepository;
import ru.mail.jira.plugins.groovy.api.repository.ExecutionRepository;
import ru.mail.jira.plugins.groovy.api.service.ScriptService;
import ru.mail.jira.plugins.groovy.api.dto.listener.EventListenerDto;
import ru.mail.jira.plugins.groovy.api.dto.listener.EventListenerForm;
import ru.mail.jira.plugins.groovy.api.dto.listener.ConditionType;
import ru.mail.jira.plugins.groovy.api.dto.listener.ScriptedEventListener;
import ru.mail.jira.plugins.groovy.api.dto.listener.ConditionDescriptor;
import ru.mail.jira.plugins.groovy.util.cl.DelegatingClassLoader;

import javax.annotation.Nonnull;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

@Component
@ExportAsDevService
public class EventListenerRepositoryImpl implements EventListenerRepository {
    private static final String VALUE_KEY = "value";

    private final Logger logger = LoggerFactory.getLogger(EventListenerRepositoryImpl.class);

    private final Cache<String, List<ScriptedEventListener>> cache;
    private final ActiveObjects ao;
    private final I18nHelper i18nHelper;
    private final ListenerDao listenerDao;
    private final JsonMapper jsonMapper;
    private final ChangelogHelper changelogHelper;
    private final ScriptService scriptService;
    private final ExecutionRepository executionRepository;
    private final DelegatingClassLoader classLoader;

    @Autowired
    public EventListenerRepositoryImpl(
        @ComponentImport CacheManager cacheManager,
        @ComponentImport ActiveObjects ao,
        @ComponentImport I18nHelper i18nHelper,
        ListenerDao listenerDao,
        JsonMapper jsonMapper,
        ChangelogHelper changelogHelper,
        ScriptService scriptService,
        ExecutionRepository executionRepository,
        DelegatingClassLoader classLoader
    ) {
        cache = cacheManager.getCache(EventListenerRepositoryImpl.class.getCanonicalName() + ".cache",
            new EventListenerCacheLoader(),
            new CacheSettingsBuilder()
                .expireAfterAccess(30, TimeUnit.MINUTES)
                .remote()
                .replicateViaInvalidation()
                .build()
        );
        this.ao = ao;
        this.i18nHelper = i18nHelper;
        this.listenerDao = listenerDao;
        this.jsonMapper = jsonMapper;
        this.changelogHelper = changelogHelper;
        this.scriptService = scriptService;
        this.executionRepository = executionRepository;
        this.classLoader = classLoader;
    }

    @Override
    public List<ScriptedEventListener> getAllListeners() {
        return cache.get(VALUE_KEY);
    }

    @Override
    public List<EventListenerDto> getListeners(boolean includeChangelogs, boolean includeErrorCount) {
        return Arrays
            .stream(ao.find(Listener.class, Query.select().where("DELETED = ?", false)))
            .map(listener -> buildDto(listener, includeChangelogs, includeErrorCount))
            .collect(Collectors.toList());
    }

    @Override
    public EventListenerDto getEventListener(int id) {
        return buildDto(ao.get(Listener.class, id), true, true);
    }

    @Override
    public EventListenerDto createEventListener(ApplicationUser user, EventListenerForm form) {
        validateListener(true, form);

        Listener listener = listenerDao.createEventListener(user, form);

        cache.remove(VALUE_KEY);

        return buildDto(listener, true, true);
    }

    @Override
    public EventListenerDto updateEventListener(ApplicationUser user, int id, EventListenerForm form) {
        validateListener(false, form);

        Listener listener = listenerDao.updateEventListener(user, id, form);

        cache.remove(VALUE_KEY);

        return buildDto(listener, true, true);
    }

    @Override
    public void deleteEventListener(ApplicationUser user, int id) {
        listenerDao.deleteEventListener(user, id);

        cache.remove(VALUE_KEY);
    }

    @Override
    public void restoreEventListener(ApplicationUser user, int id) {
        listenerDao.restoreEventListener(user, id);

        cache.remove(VALUE_KEY);
    }

    @Override
    public void invalidate() {
        cache.removeAll();
    }

    private EventListenerDto buildDto(Listener listener, boolean includeChangelogs, boolean includeErrorCount) {
        EventListenerDto result = new EventListenerDto();
        result.setId(listener.getID());
        result.setName(listener.getName());
        result.setDescription(listener.getDescription());
        result.setScriptBody(listener.getScriptBody());
        result.setUuid(listener.getUuid());
        result.setCondition(jsonMapper.read(listener.getCondition(), ConditionDescriptor.class));

        if (includeChangelogs) {
            result.setChangelogs(changelogHelper.collect(listener.getChangelogs()));
        }

        if (includeErrorCount) {
            result.setErrorCount(executionRepository.getErrorCount(listener.getUuid()));
            result.setWarningCount(executionRepository.getWarningCount(listener.getUuid()));
        }

        return result;
    }

    private void validateListener(boolean isNew, EventListenerForm form) {
        ValidationUtils.validateForm(i18nHelper, isNew, form);

        if (StringUtils.isEmpty(form.getScriptBody())) {
            throw new RestFieldException(i18nHelper.getText("ru.mail.jira.plugins.groovy.error.fieldRequired"), "scriptBody");
        }

        scriptService.parseScript(form.getScriptBody());

        ConditionDescriptor condition = form.getCondition();
        if (condition == null) {
            throw new RestFieldException(i18nHelper.getText("ru.mail.jira.plugins.groovy.error.fieldRequired"), "condition.type");
        }

        if (condition.getType() == null) {
            throw new RestFieldException(i18nHelper.getText("ru.mail.jira.plugins.groovy.error.fieldRequired"), "condition.type");
        }

        if (condition.getType() == ConditionType.CLASS_NAME) {
            condition.setClassName(StringUtils.trimToNull(condition.getClassName()));

            if (condition.getClassName() == null) {
                throw new RestFieldException(i18nHelper.getText("ru.mail.jira.plugins.groovy.error.fieldRequired"), "condition.className");
            }

            try {
                classLoader.getJiraClassLoader().loadClass(condition.getClassName());
            } catch (ClassNotFoundException e) {
                throw new RestFieldException("Unable to resolve class: " + e.getMessage(), "condition.className");
            }
        } else {
            if (condition.getProjectIds() == null) {
                condition.setProjectIds(ImmutableSet.of());
            }
            if (condition.getTypeIds() == null) {
                condition.setTypeIds(ImmutableSet.of());
            }
            if (condition.getType() == ConditionType.ISSUE) {
                condition.setClassName(null);
            } else {
                condition.setProjectIds(null);
                condition.setTypeIds(null);
            }
        }
    }

    private ScriptedEventListener buildEventListener(Listener listener) throws ClassNotFoundException {
        ConditionDescriptor descriptor = jsonMapper.read(listener.getCondition(), ConditionDescriptor.class);
        if (descriptor.getClassName() != null) {
            descriptor.setClassInstance(classLoader.getJiraClassLoader().loadClass(descriptor.getClassName()));
        }
        return new ScriptedEventListener(
            listener.getID(),
            listener.getScriptBody(),
            listener.getUuid(),
            descriptor
        );
    }

    private class EventListenerCacheLoader implements CacheLoader<String, List<ScriptedEventListener>> {
        @Nonnull
        @Override
        public List<ScriptedEventListener> load(@Nonnull String key) {
            if (Objects.equals(VALUE_KEY, key)) {
                return Arrays
                    .stream(ao.find(Listener.class, Query.select().where("DELETED = ?", false)))
                    .map(listener -> {
                        try {
                            return buildEventListener(listener);
                        } catch (ClassNotFoundException e) {
                            logger.error("unable to load class for condition", e);
                            return null;
                        }
                    })
                    .filter(Objects::nonNull)
                    .collect(Collectors.toList());
            } else {
                return ImmutableList.of();
            }
        }
    }
}
