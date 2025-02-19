package org.carlspring.strongbox.data;

/**
 * @author Przemyslaw Fusik
 */
public final class CacheName {

    public static final class User {

        public static final String AUTHENTICATIONS = "authentications";

        private User() {
        }
    }

    public static final class Artifact {

        public static final String TAGS = "tags";

        private Artifact() {
        }
    }

    public static final class Repository {

        public static final String REMOTE_REPOSITORY_ALIVENESS = "remoteRepositoryAliveness";

        private Repository() {
        }
    }
}
