package ma.mystix.tenant;

/** Published inside the registration transaction, so other modules can provision the new environment. */
public record CompanyRegistered(Company company) {
}
