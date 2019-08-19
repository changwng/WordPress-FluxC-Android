package org.wordpress.android.fluxc.store.stats.time

import kotlinx.coroutines.withContext
import org.wordpress.android.fluxc.model.SiteModel
import org.wordpress.android.fluxc.model.stats.LimitMode
import org.wordpress.android.fluxc.model.stats.time.TimeStatsMapper
import org.wordpress.android.fluxc.model.stats.time.VisitsAndViewsModel
import org.wordpress.android.fluxc.network.rest.wpcom.stats.time.StatsUtils
import org.wordpress.android.fluxc.network.rest.wpcom.stats.time.VisitAndViewsRestClient
import org.wordpress.android.fluxc.network.utils.StatsGranularity
import org.wordpress.android.fluxc.persistence.TimeStatsSqlUtils.VisitsAndViewsSqlUtils
import org.wordpress.android.fluxc.store.StatsStore.OnStatsFetched
import org.wordpress.android.fluxc.store.StatsStore.StatsError
import org.wordpress.android.fluxc.store.StatsStore.StatsErrorType.INVALID_RESPONSE
import org.wordpress.android.fluxc.utils.CurrentTimeProvider
import org.wordpress.android.fluxc.utils.SiteUtils
import java.util.Date
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.CoroutineContext

@Singleton
class VisitsAndViewsStore
@Inject constructor(
    private val restClient: VisitAndViewsRestClient,
    private val sqlUtils: VisitsAndViewsSqlUtils,
    private val timeStatsMapper: TimeStatsMapper,
    private val statsUtils: StatsUtils,
    private val currentTimeProvider: CurrentTimeProvider,
    private val coroutineContext: CoroutineContext
) {
    suspend fun fetchVisits(
        site: SiteModel,
        granularity: StatsGranularity,
        limitMode: LimitMode.Top,
        forced: Boolean = false
    ) = withContext(coroutineContext) {
        val dateWithTimeZone = statsUtils.getFormattedDate(
                currentTimeProvider.currentDate,
                SiteUtils.getNormalizedTimezone(site.timezone)
        )
        if (!forced && sqlUtils.hasFreshRequest(site, granularity, dateWithTimeZone, limitMode.limit)) {
            return@withContext OnStatsFetched(getVisits(site, granularity, limitMode, dateWithTimeZone), cached = true)
        }
        val payload = restClient.fetchVisits(site, granularity, dateWithTimeZone, limitMode.limit, forced)
        return@withContext when {
            payload.isError -> OnStatsFetched(payload.error)
            payload.response != null -> {
                sqlUtils.insert(site, payload.response, granularity, dateWithTimeZone, limitMode.limit)
                val overviewResponse = timeStatsMapper.map(payload.response, limitMode)
                if (overviewResponse.period.isBlank() || overviewResponse.dates.isEmpty())
                    OnStatsFetched(StatsError(INVALID_RESPONSE, "Overview: Required data 'period' or 'dates' missing"))
                else
                    OnStatsFetched(overviewResponse)
            }
            else -> OnStatsFetched(StatsError(INVALID_RESPONSE))
        }
    }

    fun getVisits(
        site: SiteModel,
        granularity: StatsGranularity,
        limitMode: LimitMode,
        date: Date
    ): VisitsAndViewsModel? {
        val dateWithTimeZone = statsUtils.getFormattedDate(
                date,
                SiteUtils.getNormalizedTimezone(site.timezone)
        )
        return getVisits(site, granularity, limitMode, dateWithTimeZone)
    }

    private fun getVisits(
        site: SiteModel,
        granularity: StatsGranularity,
        limitMode: LimitMode,
        dateWithTimeZone: String
    ): VisitsAndViewsModel? {
        return sqlUtils.select(site, granularity, dateWithTimeZone)?.let { timeStatsMapper.map(it, limitMode) }
    }
}
