package id.walt.idp.oidc

import io.javalin.core.security.RouteRole

enum class OIDCAuthorizationRole : RouteRole {
  UNAUTHORIZED,
  OIDC_CLIENT,
  ACCESS_TOKEN
}
