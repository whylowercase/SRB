package com.hy.srb.core.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.hy.common.exception.Assert;
import com.hy.common.result.ResponseEnum;
import com.hy.srb.base.dto.SmsDTO;
import com.hy.srb.core.enums.TransTypeEnum;
import com.hy.srb.core.hfb.FormHelper;
import com.hy.srb.core.hfb.HfbConst;
import com.hy.srb.core.hfb.RequestHelper;
import com.hy.srb.core.mapper.UserAccountMapper;
import com.hy.srb.core.mapper.UserInfoMapper;
import com.hy.srb.core.pojo.bo.TransFlowBO;
import com.hy.srb.core.pojo.entity.UserAccount;
import com.hy.srb.core.pojo.entity.UserInfo;
import com.hy.srb.core.service.TransFlowService;
import com.hy.srb.core.service.UserAccountService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hy.srb.core.service.UserBindService;
import com.hy.srb.core.service.UserInfoService;
import com.hy.srb.core.util.LendNoUtils;
import com.hy.srb.rabbitutil.config.MQConfig;
import com.hy.srb.rabbitutil.constant.MQConst;
import com.hy.srb.rabbitutil.service.MQService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;

/**
 * <p>
 * 用户账户 服务实现类
 * </p>
 *
 * @author jeremy
 * @since 2022-07-14
 */
@Service
@Slf4j
public class UserAccountServiceImpl extends ServiceImpl<UserAccountMapper, UserAccount> implements UserAccountService {
    @Resource
    private UserInfoMapper userInfoMapper;
    @Resource
    private TransFlowService transFlowService;
    @Resource
    private UserAccountService userAccountService;
    @Resource
    private UserBindService userBindService;

    @Resource
    private UserInfoService userInfoService;
    @Resource
    private MQService mqService;
    @Override
    public String commitCharge(BigDecimal chargeAmt, Long userId) {
        //获取充值人绑定协议号
        UserInfo userInfo = userInfoMapper.selectById(userId);
        String bindCode = userInfo.getBindCode();

        HashMap<String, Object> paramMap = new HashMap<>();
        paramMap.put("agentId", HfbConst.AGENT_ID);
        paramMap.put("agentBillNo", LendNoUtils.getChargeNo());
        paramMap.put("bingCode",bindCode);
        paramMap.put("chargeAmt",chargeAmt);
        paramMap.put("feeAmt", new BigDecimal("0"));
        paramMap.put("notifyUrl",HfbConst.RECHARGE_NOTIFY_URL);
        paramMap.put("returnUrl",HfbConst.RECHARGE_RETURN_URL);
        paramMap.put("timestamp", RequestHelper.getTimestamp());
        paramMap.put("sign",RequestHelper.getSign(paramMap));

        String formStr = FormHelper.buildForm(HfbConst.RECHARGE_URL, paramMap);
        return formStr;
    }

    @Override
    public String notify(Map<String, Object> paramMap) {
        String agentBillNo = (String)paramMap.get("agentBillNo");
        //幂等性判断..判断交易流水是否存在
        boolean isSave = transFlowService.isSaveTransFlow(agentBillNo);
        if(isSave){
            log.warn("幂等性返回");
            return "success";
        }

        //账户处理
        String bindCode = (String) paramMap.get("bindCode");
        String chargeAmt = (String) paramMap.get("chargeAmt");
        baseMapper.updateAccount(bindCode,new BigDecimal(chargeAmt),new BigDecimal(0));

        //记录账户流水
        TransFlowBO transFlowBO = new TransFlowBO(
                agentBillNo,
                bindCode,
                new BigDecimal(chargeAmt),
                TransTypeEnum.RECHARGE,
                "充值啦");

        transFlowService.saveTransFlow(transFlowBO);

        //发消息
        String mobileByBindCode = userInfoService.getMobileByBindCode(bindCode);
        SmsDTO smsDTO = new SmsDTO();
        smsDTO.setMobile(mobileByBindCode);
        smsDTO.setMessage("充值成功");
        mqService.sendMessage(
                MQConst.EXCHANGE_TOPIC_SMS,
                MQConst.ROUTING_SMS_ITEM,
                smsDTO
        );

        return "success";
    }

    @Override
    public BigDecimal getAccount(Long userId) {

        QueryWrapper<UserAccount> userAccountQueryWrapper = new QueryWrapper<>();
        userAccountQueryWrapper.eq("user_id",userId);
        UserAccount userAccount = baseMapper.selectOne(userAccountQueryWrapper);
        return userAccount.getAmount();

    }

    @Override
    public String commitWithdraw(BigDecimal fetchAmt, Long userId) {
        //用户账户余额
        BigDecimal amount = userAccountService.getAccount(userId);
        Assert.isTrue(amount.doubleValue() >= fetchAmt.doubleValue(),
                ResponseEnum.NOT_SUFFICIENT_FUNDS_ERROR);

        String bindCode = userBindService.getBindCodeByUserId(userId);

        Map<String, Object> paramMap = new HashMap<>();
        paramMap.put("agentId", HfbConst.AGENT_ID);
        paramMap.put("agentBillNo", LendNoUtils.getWithdrawNo());
        paramMap.put("bindCode", bindCode);
        paramMap.put("fetchAmt", fetchAmt);
        paramMap.put("feeAmt", new BigDecimal(0));
        paramMap.put("notifyUrl", HfbConst.WITHDRAW_NOTIFY_URL);
        paramMap.put("returnUrl", HfbConst.WITHDRAW_RETURN_URL);
        paramMap.put("timestamp", RequestHelper.getTimestamp());
        String sign = RequestHelper.getSign(paramMap);
        paramMap.put("sign", sign);

        //构建自动提交表单
        String formStr = FormHelper.buildForm(HfbConst.WITHDRAW_URL, paramMap);
        return formStr;
    }

    @Override
    public void notifyWithdraw(Map<String, Object> paramMap) {
        //幂等判断
        log.info("提现成功");
        String agentBillNo = (String)paramMap.get("agentBillNo");
        boolean result = transFlowService.isSaveTransFlow(agentBillNo);
        if(result){
            log.warn("幂等性返回");
            return;
        }

        //账户同步
        String bindCode = (String)paramMap.get("bindCode");
        String fetchAmt = (String)paramMap.get("fetchAmt");
        baseMapper.updateAccount(bindCode, new BigDecimal("-" + fetchAmt), new BigDecimal(0));

        //交易流水
        TransFlowBO transFlowBO = new TransFlowBO(
                agentBillNo,
                bindCode,
                new BigDecimal(fetchAmt),
                TransTypeEnum.WITHDRAW,
                "提现啦");
        transFlowService.saveTransFlow(transFlowBO);
    }

}
