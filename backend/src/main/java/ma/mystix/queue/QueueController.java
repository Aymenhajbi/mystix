package ma.mystix.queue;

import java.util.List;
import java.util.UUID;

import jakarta.servlet.http.HttpServletRequest;
import ma.mystix.auth.Access;
import ma.mystix.tenant.CompanyService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Configuration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The queue of the client environment named by {@code X-Mystix-Company-Id} (ADR-0003): depth, jobs by status, and
 * replay of a DEAD job (administrator of the environment).
 */
@RestController
@RequestMapping(value = "/api/v1/queue", produces = MediaType.APPLICATION_JSON_VALUE)
class QueueController {

    private static final Logger LOG = LoggerFactory.getLogger(QueueController.class);
    private static final String COMPANY = "X-Mystix-Company-Id";

    private final MessageQueue queue;
    private final CompanyService companies;
    private final Access access;

    QueueController(MessageQueue queue, CompanyService companies, Access access) {
        this.queue = queue;
        this.companies = companies;
        this.access = access;
    }

    @GetMapping("/stats")
    MessageQueue.QueueStats stats(@RequestHeader(COMPANY) UUID companyId) {
        companies.get(companyId);
        return queue.stats(companyId);
    }

    @GetMapping("/jobs")
    List<Job> jobs(@RequestHeader(COMPANY) UUID companyId, @RequestParam(required = false) Job.Status status,
                   @RequestParam(defaultValue = "100") int limit) {
        companies.get(companyId);
        return queue.list(companyId, status, limit);
    }

    @GetMapping("/jobs/{id}")
    Job job(@RequestHeader(COMPANY) UUID companyId, @PathVariable UUID id) {
        return queue.get(companyId, id);
    }

    @PostMapping("/jobs/{id}/replay")
    Job replay(@RequestHeader(COMPANY) UUID companyId, @PathVariable UUID id, HttpServletRequest request) {
        access.requireManager(request, companyId);
        Job job = queue.replay(companyId, id);
        LOG.info("Job {} ({}) replayed by {}", id, job.kind(), access.actor(request));
        return job;
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(QueueProperties.class)
    static class QueueConfig {
    }
}
