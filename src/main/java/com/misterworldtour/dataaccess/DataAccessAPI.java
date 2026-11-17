package com.misterworldtour.dataaccess;

import com.misterworldtour.members.MemberDataAccessAPI;
import com.misterworldtour.sms.SMSMessageDataAccessAPI;
import com.misterworldtour.souvenir.SouvenirDataAccessAPI;
import com.misterworldtour.travel.TravelDataAccessAPI;

/**
 * 회원·여행·기념품·문자 기록 데이터의 저장과 조회 (설계 7.1 DataAccessAPI). {@link DataAccessManager} 가 구현한다.
 *
 * <p>메소드는 각 업무 서브시스템이 가진 인터페이스({@link MemberDataAccessAPI}, {@link TravelDataAccessAPI},
 * {@link SouvenirDataAccessAPI}, {@link SMSMessageDataAccessAPI})에 정의되어 있고, 이 인터페이스는 그것을 하나로 묶는다.
 * 업무 서브시스템은 자기 인터페이스만 알고 Data Access 를 직접 참조하지 않으므로, 의존은 Data Access → 업무 서브시스템
 * 한 방향뿐이다 (의존성 역전).
 *
 * <p>트랜잭션 경계는 호출하는 업무 메소드가 정하고, 같은 상품을 잠그고 여러 조회·저장을 묶을 때는
 * {@link TravelDataAccessAPI#runTravelTransaction} 을 쓴다.
 */
public interface DataAccessAPI
        extends MemberDataAccessAPI, TravelDataAccessAPI, SouvenirDataAccessAPI, SMSMessageDataAccessAPI {
}
