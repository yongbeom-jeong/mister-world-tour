package com.misterworldtour.souvenir;

import com.misterworldtour.travel.Theme;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

/** Data Access: 기념품. */
interface SouvenirRepository extends JpaRepository<Souvenir, Long> {

    List<Souvenir> findByActiveTrueOrderByThemeAscNameAsc();

    List<Souvenir> findByThemeAndActiveTrueOrderByIdAsc(Theme theme);

    Optional<Souvenir> findByIdAndActiveTrue(Long id);

    Optional<Souvenir> findByNameKey(String nameKey);

    /** 현재 재고를 DB 에서 바로 읽는다 (영속성 컨텍스트의 옛 값이 아니라). */
    @Query("select s.stockQuantity from Souvenir s where s.id = :id")
    int currentStock(@Param("id") Long id);

    /** 재고가 needed 이상일 때만 차감 (음수가 되지 않는 조건부 UPDATE). 바뀐 행 수를 돌려준다. */
    @Modifying(flushAutomatically = true)
    @Query("update Souvenir s set s.stockQuantity = s.stockQuantity - :needed "
            + "where s.id = :id and s.stockQuantity >= :needed")
    int decreaseIfEnough(@Param("id") Long id, @Param("needed") int needed);

    /** 사용 중인 품목만 재고를 늘린다. 바뀐 행 수를 돌려준다. */
    @Modifying(flushAutomatically = true)
    @Query("update Souvenir s set s.stockQuantity = s.stockQuantity + :quantity where s.id = :id and s.active = true")
    int increaseIfActive(@Param("id") Long id, @Param("quantity") int quantity);
}
