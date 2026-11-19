package com.misterworldtour.dataaccess;

import com.misterworldtour.members.MemberDataAccessAPI;
import com.misterworldtour.sms.SMSMessageDataAccessAPI;
import com.misterworldtour.souvenir.SouvenirDataAccessAPI;
import com.misterworldtour.travel.TravelDataAccessAPI;

/**
 * 데이터 저장·조회 (설계 7.1 DataAccessAPI). 업무 서브시스템마다 가진 *DataAccessAPI 를 하나로 묶는다 (의존성 역전).
 * 같은 상품을 잠그고 묶을 때는 {@link TravelDataAccessAPI#runTravelTransaction}.
 */
public interface DataAccessAPI
        extends MemberDataAccessAPI, TravelDataAccessAPI, SouvenirDataAccessAPI, SMSMessageDataAccessAPI {
}
