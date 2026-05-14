package com.eventfulcommerce.product.config

import com.eventfulcommerce.common.OutboxEvent
import com.eventfulcommerce.common.OutboxEventService
import com.eventfulcommerce.common.OutboxStatus
import com.eventfulcommerce.common.ProductRegisteredPayload
import com.fasterxml.jackson.databind.ObjectMapper
import com.eventfulcommerce.product.domain.ProductCategory
import com.eventfulcommerce.product.domain.ProductStatus
import com.eventfulcommerce.product.domain.entity.Product
import com.eventfulcommerce.product.repository.ProductRepository
import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.boot.CommandLineRunner
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

// [테스트용] 서비스 기동 시 꽃 상품 50개를 자동 생성합니다.
// TEST_SELLER_ID는 user-service DataInitializer.TEST_SELLER_ID와 동일한 값을 사용합니다.
// 이미 FLOWERS 카테고리 상품이 존재하면 skip됩니다 (idempotent).
@Component
class DataInitializer(
    private val productRepository: ProductRepository,
    private val outboxEventService: OutboxEventService,
    private val objectMapper: ObjectMapper
) : CommandLineRunner {

    private val logger = KotlinLogging.logger {}

    // user-service DataInitializer.TEST_SELLER_ID와 반드시 동일해야 합니다.
    private val testSellerId: UUID = UUID.fromString("11111111-1111-1111-1111-111111111111")

    private data class FlowerSeed(val name: String, val description: String, val price: Long, val stock: Int)

    private val flowers = listOf(
        FlowerSeed("장미", "사랑과 열정을 상징하는 꽃. 붉은 장미는 깊은 애정을 표현합니다.", 8000L, 100),
        FlowerSeed("튤립", "봄의 전령사. 다양한 색상으로 화사한 분위기를 연출합니다.", 5000L, 150),
        FlowerSeed("해바라기", "태양을 닮은 밝고 생기 넘치는 꽃. 행복과 활력을 상징합니다.", 4500L, 80),
        FlowerSeed("백합", "순결과 우아함의 상징. 은은한 향기가 공간을 가득 채웁니다.", 7000L, 90),
        FlowerSeed("카네이션", "어버이날의 대표 꽃. 사랑과 감사의 마음을 전합니다.", 3500L, 200),
        FlowerSeed("국화", "고귀함과 장수를 상징하는 꽃. 다양한 색상으로 풍성한 느낌을 줍니다.", 4000L, 120),
        FlowerSeed("라벤더", "보랏빛 물결이 아름다운 꽃. 진정 효과가 있는 향기로 유명합니다.", 6000L, 70),
        FlowerSeed("수선화", "이른 봄을 알리는 노란 꽃. 재생과 희망을 상징합니다.", 5500L, 60),
        FlowerSeed("아이리스", "무지개를 닮은 다채로운 색상의 꽃. 지혜와 용기를 상징합니다.", 6500L, 75),
        FlowerSeed("히아신스", "진한 향기로 유명한 봄꽃. 청초한 보랏빛이 매력적입니다.", 5000L, 85),
        FlowerSeed("프리지아", "달콤한 향기를 가진 꽃. 순결과 청순함을 상징합니다.", 4500L, 110),
        FlowerSeed("거베라", "선명한 색상의 밝고 화사한 꽃. 희망과 긍정의 에너지를 전합니다.", 5500L, 95),
        FlowerSeed("칼라", "우아한 나팔 형태의 꽃. 순결과 고귀함을 상징하는 고급스러운 꽃입니다.", 9000L, 50),
        FlowerSeed("작약", "탐스러운 꽃잎이 풍성한 꽃. 부귀영화와 행복을 상징합니다.", 8500L, 60),
        FlowerSeed("금잔화", "황금빛 꽃잎이 특징인 허브 꽃. 피부 진정 효과로도 알려져 있습니다.", 3000L, 130),
        FlowerSeed("코스모스", "가을 들판을 수놓는 여린 꽃. 순수와 소녀적인 아름다움을 상징합니다.", 2500L, 160),
        FlowerSeed("수국", "크고 풍성한 꽃송이가 특징. 진심과 헌신을 상징합니다.", 7500L, 70),
        FlowerSeed("안스리움", "광택 있는 붉은 포엽이 독특한 열대 꽃. 열정과 환대를 상징합니다.", 10000L, 40),
        FlowerSeed("양귀비", "얇고 고운 꽃잎이 매력적인 꽃. 위로와 평안을 상징합니다.", 4000L, 90),
        FlowerSeed("데이지", "단순하고 사랑스러운 흰 꽃. 순수함과 밝은 미래를 상징합니다.", 3000L, 140),
        FlowerSeed("팬지", "나비 모양의 귀여운 꽃. 사려깊은 생각과 추억을 상징합니다.", 2500L, 150),
        FlowerSeed("나팔꽃", "이른 아침을 장식하는 나팔 모양의 꽃. 애정과 결속을 상징합니다.", 2000L, 120),
        FlowerSeed("채송화", "다양한 색상의 작고 앙증맞은 꽃. 천진난만함을 상징합니다.", 1500L, 180),
        FlowerSeed("수레국화", "맑고 투명한 파란빛의 들꽃. 행운과 행복을 상징합니다.", 3500L, 100),
        FlowerSeed("민들레", "노란 꽃과 흰 솜털이 인상적인 꽃. 소박한 사랑과 희망을 상징합니다.", 1500L, 200),
        FlowerSeed("개나리", "봄을 가장 먼저 알리는 노란 꽃. 희망과 기대를 상징합니다.", 2000L, 100),
        FlowerSeed("진달래", "연분홍빛 꽃잎이 산을 물들이는 봄꽃. 사랑과 그리움을 상징합니다.", 3000L, 80),
        FlowerSeed("철쭉", "진달래와 비슷하지만 더욱 화사한 봄꽃. 첫사랑의 기쁨을 상징합니다.", 3500L, 75),
        FlowerSeed("목련", "이른 봄 순백의 꽃잎을 피우는 고귀한 꽃. 숭고함과 자연애를 상징합니다.", 6000L, 55),
        FlowerSeed("동백", "겨울에도 붉게 피는 꽃. 깊은 사랑과 기다림을 상징합니다.", 7000L, 60),
        FlowerSeed("매화", "이른 봄 추위를 이겨내는 꽃. 절개와 불굴의 의지를 상징합니다.", 5000L, 65),
        FlowerSeed("무궁화", "우리나라 국화. 근면, 성실, 인내를 상징합니다.", 3000L, 110),
        FlowerSeed("연꽃", "진흙 속에서도 청결하게 피는 꽃. 청정과 깨달음을 상징합니다.", 8000L, 45),
        FlowerSeed("능소화", "여름 담장을 수놓는 주황빛 꽃. 그리움과 기다림을 상징합니다.", 4500L, 70),
        FlowerSeed("접시꽃", "키 큰 줄기에 풍성하게 피는 꽃. 풍요와 번영을 상징합니다.", 3500L, 85),
        FlowerSeed("참나리", "주황빛 바탕에 검은 점이 특징인 야생 백합. 강인함을 상징합니다.", 5500L, 60),
        FlowerSeed("원추리", "하루만 피는 꽃으로 유명한 노란 꽃. 그리움을 잊게 해준다는 전설이 있습니다.", 4000L, 80),
        FlowerSeed("패랭이꽃", "작고 섬세한 꽃잎이 특징인 꽃. 순수한 사랑을 상징합니다.", 3000L, 90),
        FlowerSeed("제비꽃", "이른 봄 풀밭에 피는 보라색 작은 꽃. 겸손과 성실함을 상징합니다.", 2500L, 100),
        FlowerSeed("할미꽃", "솜털로 덮인 독특한 자태의 꽃. 조상의 희생과 헌신을 기리는 꽃입니다.", 4000L, 55),
        FlowerSeed("모란", "꽃의 왕으로 불리는 화려한 꽃. 부귀와 영화를 상징합니다.", 12000L, 35),
        FlowerSeed("벚꽃", "봄의 상징이자 낭만의 꽃. 삶의 아름다움과 덧없음을 표현합니다.", 6000L, 70),
        FlowerSeed("복사꽃", "연분홍빛 복숭아나무 꽃. 봄의 생명력과 낭만을 상징합니다.", 4500L, 65),
        FlowerSeed("스타티스", "드라이플라워로도 유명한 보라색 꽃. 변치 않는 기억을 상징합니다.", 4000L, 95),
        FlowerSeed("알스트로메리아", "화려한 무늬가 특징인 페루산 꽃. 우정과 헌신을 상징합니다.", 5500L, 80),
        FlowerSeed("델피늄", "하늘빛 파란 꽃이 인상적인 꽃. 밝음과 고귀함을 상징합니다.", 6500L, 60),
        FlowerSeed("리시안서스", "장미와 비슷한 모양의 우아한 꽃. 감사와 카리스마를 상징합니다.", 7500L, 50),
        FlowerSeed("스위트피", "나비처럼 생긴 꽃잎의 넝쿨식물 꽃. 섬세한 즐거움을 상징합니다.", 5000L, 75),
        FlowerSeed("물망초", "하늘색의 작은 꽃. '나를 잊지 마세요'라는 꽃말로 유명합니다.", 3500L, 90),
        FlowerSeed("용담", "가을 산야를 수놓는 보라색 꽃. 슬픔이 있어도 사랑한다는 꽃말을 가집니다.", 5000L, 60),
    )

    @Transactional
    override fun run(vararg args: String?) {
        if (productRepository.findByStatusAndCategory(ProductStatus.ACTIVE, ProductCategory.FLOWERS).isNotEmpty()) {
            logger.info { "[테스트용] FLOWERS 카테고리 상품이 이미 존재합니다. 시드 데이터 생성을 skip합니다." }
            return
        }

        val products = flowers.map { seed ->
            Product(
                sellerId = testSellerId,
                name = seed.name,
                description = seed.description,
                price = seed.price,
                stock = seed.stock,
                category = ProductCategory.FLOWERS,
                status = ProductStatus.ACTIVE
            )
        }

        val savedProducts = productRepository.saveAll(products)

        val outboxEvents = savedProducts.map { product ->
            val payload = ProductRegisteredPayload(
                productId = product.id,
                sellerId = product.sellerId,
                name = product.name,
                price = product.price,
                initialStock = product.stock,
                category = product.category.name
            )

            OutboxEvent(
                aggregateType = "PRODUCT",
                aggregateId = product.id,
                eventType = "PRODUCT_REGISTERED",
                payload = objectMapper.writeValueAsString(payload),
                status = OutboxStatus.PENDING
            )
        }

        outboxEventService.record(outboxEvents)
        logger.info { "[테스트용] 꽃 상품 ${savedProducts.size}개 생성 및 PRODUCT_REGISTERED Outbox 기록 완료 (sellerId=$testSellerId)" }
    }
}
