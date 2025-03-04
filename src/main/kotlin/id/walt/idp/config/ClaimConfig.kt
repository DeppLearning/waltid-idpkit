package id.walt.idp.config

import com.jayway.jsonpath.JsonPath
import com.nimbusds.jwt.JWTClaimsSet
import com.nimbusds.oauth2.sdk.Scope
import id.walt.idp.nfts.ChainEcosystem
import id.walt.idp.nfts.NFTManager
import id.walt.idp.nfts.NftTokenClaim
import id.walt.idp.nfts.NftTokenConstraint
import id.walt.idp.oidc.OIDCManager
import id.walt.idp.oidc.ResponseVerificationResult
import id.walt.model.oidc.VpTokenClaim
import io.javalin.http.BadRequestResponse

abstract class ClaimMapping(
    val scope: Set<String>,
    val claim: String
) {
    abstract fun fillClaims(verificationResult: ResponseVerificationResult, claimBuilder: JWTClaimsSet.Builder)
    abstract val authorizationMode: OIDCManager.AuthorizationMode
}

class VCClaimMapping(
    scope: Set<String>,
    claim: String,
    val credentialType: String,
    val valuePath: String
) : ClaimMapping(scope, claim) {
    override fun fillClaims(verificationResult: ResponseVerificationResult, claimBuilder: JWTClaimsSet.Builder) {
        val credential = verificationResult.siopResponseVerificationResult?.vps?.flatMap { it.vp.verifiableCredential ?: listOf() }
            ?.firstOrNull { c -> c.type.contains(credentialType) }
            ?: throw BadRequestResponse("vp_token from SIOP response doesn't contain required credentials")
        val jp = JsonPath.parse(credential.toJson())
        val value = valuePath.split(" ").map { jp.read<Any>(it) }.joinToString(" ")
        claimBuilder.claim(claim, value)
    }

    override val authorizationMode: OIDCManager.AuthorizationMode
        get() = OIDCManager.AuthorizationMode.SIOP
}

data class NFTClaimMappingDefinition(
    val nftTokenConstraint: NftTokenConstraint,
    val trait: String
)

class NFTClaimMapping(
    scope: Set<String>,
    claim: String,
    val claimMappings: Map<String, NFTClaimMappingDefinition>
) : ClaimMapping(scope, claim) {
    override fun fillClaims(verificationResult: ResponseVerificationResult, claimBuilder: JWTClaimsSet.Builder) {
      val mappingDefinition = verificationResult.nftresponseVerificationResult?.ecosystem?.let {
        claimMappings[it.name]
      } ?: throw BadRequestResponse("No mapping definition found for the given ecosystem")

      val claimValue = when(verificationResult.nftresponseVerificationResult.ecosystem) {
        ChainEcosystem.EVM -> verificationResult.nftresponseVerificationResult.metadata?.evmNftMetadata?.attributes?.firstOrNull { a -> a.trait_type == mappingDefinition.trait }?.value
        ChainEcosystem.TEZOS -> verificationResult.nftresponseVerificationResult.metadata?.tezosNftMetadata?.attributes?.firstOrNull { a -> a.name == mappingDefinition.trait }?.value
        ChainEcosystem.NEAR -> verificationResult.nftresponseVerificationResult.metadata?.nearNftMetadata?.metadata?.let { NFTManager.getNearNftAttributeValue(it, mappingDefinition.trait)
        }
        ChainEcosystem.POLKADOT -> verificationResult.nftresponseVerificationResult.metadata?.uniqueNftMetadata?.attributes?.let { a -> a.firstOrNull { a -> a.name == mappingDefinition.trait }?.value }
      }?: throw BadRequestResponse("Requested nft metadata trait not found in verification response")

      claimBuilder.claim(mappingDefinition.trait, claimValue)
    }

    override val authorizationMode: OIDCManager.AuthorizationMode
        get() = OIDCManager.AuthorizationMode.NFT
}


class ClaimConfig(
    val vc_mappings: List<VCClaimMapping>? = null,
    val nft_mappings: List<NFTClaimMapping>? = null,
    val default_nft_token_claim: NftTokenClaim? = null,
    val default_vp_token_claim: VpTokenClaim? = null,
    val default_nft_policy: DefaultNftPolicy? = null
) {
    fun allMappings(): List<ClaimMapping> {
        return (vc_mappings ?: listOf()).plus(nft_mappings ?: listOf())
    }

    fun mappingsForScope(scope: Scope.Value): List<ClaimMapping> {
        return allMappings()
            .filter { m -> m.scope.contains(scope.value) }
    }

    fun mappingsForClaim(claim: String): List<ClaimMapping> {
        return allMappings()
            .filter { m -> m.claim == claim }
    }

    fun credentialTypesForScope(scope: Scope.Value): Set<String> {
        return vc_mappings?.filter { m -> m.scope.contains(scope.value) }
            ?.map { m -> m.credentialType }?.toSet()
            ?: setOf()
    }

    fun credentialTypesForClaim(claim: String): Set<String> {
        return vc_mappings?.filter { m -> m.claim == claim }
            ?.map { m -> m.credentialType }?.toSet()
            ?: setOf()
    }
}

class DefaultNftPolicy(
    val withPolicyVerification: Boolean? = false,
    val policy: String,
    val query: String,
    val inputs: Map<String, Any?>
)
