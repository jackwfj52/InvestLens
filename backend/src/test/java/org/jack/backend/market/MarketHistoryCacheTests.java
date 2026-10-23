package org.jack.backend.market;

import org.jack.backend.common.exception.BusinessException;
import org.jack.backend.common.identity.CurrentOwner;
import org.jack.backend.infrastructure.resilience.OptionalRedis;
import org.jack.backend.portfolio.PortfolioDtos.DataMode;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.jack.backend.market.MarketData.*;

class MarketHistoryCacheTests {
    private final OptionalRedis redis=mock(OptionalRedis.class);
    private final MarketDataProvider provider=mock(MarketDataProvider.class);
    private final Clock clock=Clock.fixed(Instant.parse("2026-07-08T00:00:00Z"),ZoneOffset.UTC);
    private final Permissions permissions=new Permissions("ALLOWED","ALLOWED","PAYLOAD_ALLOWED");
    private final Instrument instrument=new Instrument("1.0.0","STOCK:SINA:NASDAQ:AAPL:USD","STOCK","AAPL","NASDAQ","SINA","USD","CURRENCY_PER_SHARE","America/New_York",UsEquitiesCalendar.ID,UsEquitiesCalendar.VERSION,null,Adjustment.UNADJUSTED,"Stub only");
    private final Candidate candidate=new Candidate(instrument,null,"QUALIFIED",true,permissions);
    private final Query query=new Query(instrument.instrumentId(),LocalDate.parse("2026-07-02"),LocalDate.parse("2026-07-07"));
    private final History history=new History("1.0.0",instrument,query.start(),query.end(),"1d","https://example.com",null,"2026-07-08T00:00:00.000Z",true,true,permissions,
            List.of(bar("2026-07-02"),bar("2026-07-06"),bar("2026-07-07")),List.of());
    private Bar bar(String day){return new Bar(LocalDate.parse(day),"100","100","100","100");}
    private MarketHistoryCache cache(int ttl){return new MarketHistoryCache(redis,provider,new MarketQualityService(new UsEquitiesCalendar()),new CurrentOwner(),JsonMapper.builder().build(),clock,ttl);}
    @Test void onlyExplicitCacheModeUsesSavedLicensedHistoryAndKeepsOriginalTime() {
        var saved=new AtomicReference<String>();when(provider.history(query,8000)).thenReturn(history);
        doAnswer(call->{saved.set(call.getArgument(1));return null;}).when(redis).put(anyString(),anyString(),eq(Duration.ofSeconds(60)));
        when(redis.get(anyString())).thenAnswer(call->saved.get());var cache=cache(60);
        cache.load(query,candidate,DataMode.LIVE,8000);var reused=cache.load(query,candidate,DataMode.CACHE,8000);
        assertThat(reused.retrievedAt()).isEqualTo(history.retrievedAt());assertThat(reused.bars()).isEqualTo(history.bars());verify(provider,times(1)).history(query,8000);
    }
    @Test void cacheMissCorruptionDisabledRetentionAndReplayNeverRequestLiveData() {
        var cache=cache(60);assertThatThrownBy(()->cache.load(query,candidate,DataMode.CACHE,8000)).isInstanceOf(BusinessException.class);
        when(redis.get(anyString())).thenReturn("not-json");assertThatThrownBy(()->cache.load(query,candidate,DataMode.CACHE,8000)).isInstanceOf(BusinessException.class);
        assertThatThrownBy(()->cache(0).load(query,candidate,DataMode.CACHE,8000)).isInstanceOf(BusinessException.class);
        assertThatThrownBy(()->cache.load(query,candidate,DataMode.REPLAY,8000)).isInstanceOf(BusinessException.class);verifyNoInteractions(provider);
    }
    @Test void metadataOnlyAndInvalidQualityAreNeverStored() {
        var blocked=new Candidate(instrument,null,"QUALIFIED",true,new Permissions("ALLOWED","ALLOWED","METADATA_ONLY"));when(provider.history(query,8000)).thenReturn(history);
        cache(60).load(query,blocked,DataMode.LIVE,8000);verifyNoInteractions(redis);
        var invalid=new History("1.0.0",instrument,query.start(),query.end(),"1d",history.source(),null,history.retrievedAt(),false,false,permissions,history.bars(),List.of());
        when(provider.history(query,8000)).thenReturn(invalid);cache(60).load(query,candidate,DataMode.LIVE,8000);verifyNoInteractions(redis);
    }
    @Test void concurrentLiveReadersShareOneInFlightRequest() throws Exception {
        var start=new CountDownLatch(1);var release=new CountDownLatch(1);var cache=cache(0);
        when(provider.history(query,8000)).thenAnswer(call->{start.countDown();assertThat(release.await(5,TimeUnit.SECONDS)).isTrue();return history;});
        var pool=Executors.newFixedThreadPool(2);
        try {
            var first=pool.submit(()->cache.load(query,candidate,DataMode.LIVE,8000));assertThat(start.await(3,TimeUnit.SECONDS)).isTrue();
            var second=pool.submit(()->cache.load(query,candidate,DataMode.LIVE,8000));
            Thread.sleep(50);release.countDown();assertThat(first.get(5,TimeUnit.SECONDS)).isEqualTo(second.get(5,TimeUnit.SECONDS));verify(provider,times(1)).history(query,8000);
        }finally{release.countDown();pool.shutdownNow();}
    }
}
