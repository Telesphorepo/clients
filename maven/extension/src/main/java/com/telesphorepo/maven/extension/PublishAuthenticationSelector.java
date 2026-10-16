package com.telesphorepo.maven.extension;

import com.telesphorepo.client.Protocol;
import org.eclipse.aether.repository.Authentication;
import org.eclipse.aether.repository.AuthenticationSelector;
import org.eclipse.aether.repository.RemoteRepository;
import org.eclipse.aether.util.repository.AuthenticationBuilder;

/**
 * While a deploy goal runs, gives each repository on the Telesphorepo server the publish session
 * token as its password. maven-deploy-plugin asks this selector for the credentials of every
 * deployment repository, -DaltDeploymentRepository and deploy:deploy-file included. A failed
 * session mint throws, so the deploy stops; such a repository never gets the credentials of
 * settings.xml. Every other lookup goes to the selector that Maven built from settings.xml.
 */
final class PublishAuthenticationSelector implements AuthenticationSelector {
    private final AuthenticationSelector settings;
    private final PublishState state;

    PublishAuthenticationSelector(AuthenticationSelector settings, PublishState state) {
        this.settings = settings;
        this.state = state;
    }

    @Override
    public Authentication getAuthentication(RemoteRepository repository) {
        if (state.deploying() && state.classify(repository.getUrl()) instanceof PublishState.Telesphorepo t) {
            return new AuthenticationBuilder().addUsername(Protocol.SESSION_USER).addPassword(state.token(t)).build();
        }
        return settings == null ? null : settings.getAuthentication(repository);
    }
}
