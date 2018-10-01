import java.util.*;
import com.dukascopy.api.*;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.concurrent.CopyOnWriteArrayList;
import java.lang.reflect.*;
import java.math.BigDecimal;

/* 
 * The name is an acronym of the indicators used:
 * SF = Stochastic Fast
 * I = Ichimoku
 * C = CCI 
 * S = Stochastic
 */
public class SFICS implements IStrategy {
    private CopyOnWriteArrayList<TradeEventAction> tradeEventActions = new CopyOnWriteArrayList<TradeEventAction>();
    private static final String DATE_FORMAT_NOW = "yyyyMMdd_HHmmss";
    private IEngine engine;
    private IConsole console;
    private IHistory history;
    private IContext context;
    private IIndicators indicators;
    private IUserInterface userInterface;

    @Configurable("defaultTakeProfit:")
    public int defaultTakeProfit = 250;
    @Configurable("defaultInstrument:")
    public Instrument defaultInstrument = Instrument.EURUSD;
    @Configurable("defaultSlippage:")
    public int defaultSlippage = 3;
    @Configurable("defaultStopLoss:")
    public int defaultStopLoss = 150;
    @Configurable("defaultPeriod:")
    public Period defaultPeriod = Period.ONE_MIN;
    @Configurable("dynamicTakeProfit:")
    public int dynamicTakeProfit = 10;

    // INDICATORS (STOCH, STOCHF, CCI, SAR, ICHIMOKU)
    private double _stoch_K;
    private double _stoch_D;
    private double _stochf_K;
    private double _stochf_D;
    private double _stochf_shifted_D;
    private double _stochf_shifted_K;
    private Period _stoch_period = Period.TEN_MINS;
    private int _stoch_shift = 0;
    @Configurable("Stochastic oscillator fast K: ")
    private int _stoch_fk_period = 5;
    @Configurable("Stochastic oscillator slow K: ")
    private int _stoch_sk_period = 3;
    @Configurable("Stochastic oscillator slow D: ")
    private int _stoch_sd_period = 3;
    private Period _stochf_period = Period.FIVE_MINS;
    private int _stochf_shifted_shift = 1;

    private double _cci;
    private double _shifted_cci;
    private Period _cci_period = Period.ONE_HOUR;
    @Configurable("CCI time period: ")
    public int _cci_time_period = 24; // {12,24,32,64}
    private int _cci_shift = 0;
    private int _cci_shifted_shift = 1;

    private double _sar;
    private Period _sar_period = Period.ONE_HOUR; // default: Period.DAILY
    private int _sar_shift = 0;
    private double _sar_acc = 0.01;
    private double _sar_max = 0.2;
    
    private double _tenkansen;
    private double _kijunsen;
    private double _senkouA;
    private double _senkouB;
    private double _chinkou;
    private double _tenkansen_shifted;
    private double _kijunsen_shifted;
    private double _senkouA_shifted;
    private double _senkouB_shifted;
    private double _chinkou_shifted;
    private Period _ichimoku_period = Period.ONE_HOUR;
    private int _ichimoku_shift = 0;
    private int _ichimoku_shifted_shift = 26;
    private int _ichimoku_tenkansen = 9;
    private int _ichimoku_kijunsen = 26;
    private int _ichimoku_senkou = 56;

    //Custom variables
    @Configurable("Stochastic Bear Threshold: ")
    public int _stochBearTreshold = 30; // [10,30]
    @Configurable("Stochastic Bull Threshold: ")
    public int _stochBullTreshold = 55; // [70,90]
    private double _lot;    
    private double _use_of_leverage_threshold = 100 ;
    private double _pnl_threshold = 10; 
    private double _risk_per_trade = 5e3;
    private double _max_risk = 0.1;
    @Configurable("Max lot allowed: ")
    public double _max_lot = 9.0; // [5,10]
    @Configurable("Trailing step value")
    public double _pips_trailing_step = 15; // [5,20]
    private boolean _trailed = false;
    private IOrder _last_position;

    //Account Stuff
    private String AccountId = "";
    private String AccountCurrency = "";
    private double Equity;    
    private double Leverage;
    private double UseofLeverage;
    private int OverWeekendEndLeverage;
    private int MarginCutLevel;
    private Tick LastTick =  null ;
    private IMessage LastTradeEvent =  null ;
    private Candle LastAskCandle =  null ;
    private Candle LastBidCandle =  null ;
    private List<IOrder> AllPositions =  null ;
    private List<IOrder> PendingPositions =  null ;
    private List<IOrder> OpenPositions =  null ;
    private boolean GlobalAccount;

    public void onStart(IContext context) throws JFException {
        this.context = context;
        this.engine = context.getEngine();
        this.console = context.getConsole();
        this.history = context.getHistory();
        this.indicators = context.getIndicators();
        this.userInterface = context.getUserInterface();

        subscriptionInstrumentCheck(defaultInstrument);

        ITick lastITick = context.getHistory().getLastTick(defaultInstrument);
        LastTick = new Tick(lastITick, defaultInstrument);
        IBar bidBar = context.getHistory().getBar(defaultInstrument, 
            defaultPeriod, OfferSide.BID, 1);
        IBar askBar = context.getHistory().getBar(defaultInstrument, 
            defaultPeriod, OfferSide.ASK, 1);
        LastAskCandle = new Candle(askBar, defaultPeriod, defaultInstrument, 
            OfferSide.ASK);
        LastBidCandle = new Candle(bidBar, defaultPeriod, defaultInstrument, 
            OfferSide.BID);
        
        downloadIndicators();
        subscriptionInstrumentCheck(Instrument.fromString("EUR/USD"));
        console.getOut().println("Debug Started");
    }

    private void downloadIndicators(){
        try{
            if (indicators.getIndicator("ICHIMOKUVF") == null) 
                indicators.registerDownloadableIndicator("7842","ICHIMOKUVF");
            if (indicators.getIndicator("ICHIMOKUVF") == null) 
                indicators.registerDownloadableIndicator("7842","ICHIMOKUVF");
            if (indicators.getIndicator("CCI") == null) 
                indicators.registerDownloadableIndicator("1275","CCI");
            if (indicators.getIndicator("CCI") == null)
                indicators.registerDownloadableIndicator("1275","CCI");
            if (indicators.getIndicator("SAR") == null) 
                indicators.registerDownloadableIndicator("1291","SAR");
            if (indicators.getIndicator("STOCH") == null) 
                indicators.registerDownloadableIndicator("1279","STOCH");
            if (indicators.getIndicator("STOCHF") == null) 
                indicators.registerDownloadableIndicator("8325","STOCHF");
            if (indicators.getIndicator("STOCHF") == null) 
                indicators.registerDownloadableIndicator("8325","STOCHF");
        }catch (JFException e){
            e.printStackTrace();
        }
    }

    public void onAccount(IAccount account) throws JFException {
        AccountCurrency = account.getCurrency().toString();
        Leverage = account.getLeverage();
        AccountId= account.getAccountId();
        Equity = account.getEquity();
        UseofLeverage = account.getUseOfLeverage();
        OverWeekendEndLeverage = account.getOverWeekEndLeverage();
        MarginCutLevel = account.getMarginCutLevel();
        GlobalAccount = account.isGlobal();
    }

    private void updateVariables(Instrument instrument) {
        try {
            AllPositions = engine.getOrders();
            List<IOrder> listMarket = new ArrayList<IOrder>();
            for (IOrder order: AllPositions) {
                if (order.getState().equals(IOrder.State.FILLED)){
                    listMarket.add(order);
                }
            }
            List<IOrder> listPending = new ArrayList<IOrder>();
            for (IOrder order: AllPositions) {
                if (order.getState().equals(IOrder.State.OPENED)){
                    listPending.add(order);
                }
            }
            OpenPositions = listMarket;
            PendingPositions = listPending;
        } catch(JFException e) {
            e.printStackTrace();
        }
    }

    public void onMessage(IMessage message) throws JFException {
        if (message.getOrder() != null) {
            updateVariables(message.getOrder().getInstrument());
            LastTradeEvent = message;
            for (TradeEventAction event :  tradeEventActions) {
                IOrder order = message.getOrder();
                if (order != null && event != null && 
                    message.getType().equals(event.getMessageType())&& 
                    order.getLabel().equals(event.getPositionLabel())) {

                    Method method;
                    try {
                        method = this.getClass().getDeclaredMethod(
                            event.getNextBlockId(), Integer.class);
                        method.invoke(this, new Integer[] {event.getFlowId()});
                    } catch (SecurityException e) {
                        e.printStackTrace();
                    } catch (NoSuchMethodException e) {
                        e.printStackTrace();
                    } catch (IllegalArgumentException e) {
                        e.printStackTrace();
                    } catch (IllegalAccessException e) {
                        e.printStackTrace();
                    } catch (InvocationTargetException e) {
                        e.printStackTrace();
                    } 
                    tradeEventActions.remove(event); 
                }
            }   
        }
    }

    public void onStop() throws JFException {
    }

    public void onTick(Instrument instrument, ITick tick) throws JFException {
        LastTick = new Tick(tick, instrument);
        updateVariables(instrument);
    }

    public void subscriptionInstrumentCheck(Instrument instrument) { 
        try {
              if (!context.getSubscribedInstruments().contains(instrument)) {
                  Set<Instrument> instruments = new HashSet<Instrument>();
                  instruments.add(instrument);
                  context.setSubscribedInstruments(instruments, true);
                  Thread.sleep(100);
              }
          } catch (InterruptedException e) {
              e.printStackTrace();
          }
    }

    public double round(double price, Instrument instrument) {
        BigDecimal big = new BigDecimal("" + price); 
        big = big.setScale(instrument.getPipScale() + 1, BigDecimal.ROUND_HALF_UP); 
        return big.doubleValue(); 
    }

    public ITick getLastTick(Instrument instrument) {
        try { 
            return (context.getHistory().getTick(instrument, 0)); 
        } catch (JFException e) { 
             e.printStackTrace();  
         } 
         return null; 
    }

    public void onBar(Instrument instrument, Period period, 
        IBar askBar, IBar bidBar) throws JFException {
        
        LastAskCandle = new Candle(askBar, period, instrument, OfferSide.ASK);
        LastBidCandle = new Candle(bidBar, period, instrument, OfferSide.BID);
        updateVariables(instrument);
        if(validBar(instrument, period)){
            createIndicators();
            strategy();
        }
    }

    private boolean validBar(Instrument instrument, Period period){
        return (instrument != null && instrument.equals(defaultInstrument)) && 
            (period != null && period.equals(defaultPeriod));
    }

    private void createIndicators(){
        createStoch();
        createStochF();
        createShiftedStochF();
        createCCI();
        createShiftedCCI();
        createSAR();
        createIchimoku();
        createShiftedIchimoku();
    }

    private void strategy(){
        if(AllPositions.size() == 0)
            noPositionStrategy();
        else
            openedPositionStrategy();
            
    }

    private void lotControl(){
        _lot = Equity * _max_risk/_risk_per_trade;
        _lot = (_lot > _max_lot) ? _max_lot : _lot; 
    }

    private void noPositionStrategy(){
        this._trailed = false;
        if (/*LastAskCandle.getClose() < _sar &&*/ bearishEvent()){
            MarketOrder("SELL");
        }
        else if(/*LastBidCandle.getClose() > _sar &&*/ bullishEvent()){
            MarketOrder("BUY");
        }
    }

    private boolean bearishEvent(){
        return _tenkansen < _kijunsen && 
            LastBidCandle.getClose() < _senkouB_shifted && 
            LastBidCandle.getClose() < _senkouA_shifted &&
            _stoch_K < _stoch_D && _stoch_K > _stochBearTreshold && 
            _stochf_K < _stochf_shifted_K && _stochf_K >_stochf_D;
    }

    private boolean bullishEvent(){
        return _tenkansen > _kijunsen && 
            LastBidCandle.getClose() > _senkouB_shifted && 
            LastBidCandle.getClose() > _senkouA_shifted &&
            _stoch_K > _stoch_D && _stoch_K < _stochBullTreshold && 
            _stochf_K > _stochf_shifted_K && _stochf_K <_stochf_D;
    }

    private void openedPositionStrategy(){
        if (UseofLeverage > _use_of_leverage_threshold){
            CloseAllPositons();
        }
        else if (_use_of_leverage_threshold > UseofLeverage)
            for (IOrder position : OpenPositions){
                if(position.getState() == IOrder.State.OPENED||position.getState() == IOrder.State.FILLED)
                    _last_position = position;
                /*if (_last_position.getProfitLossInPips() < _pnl_threshold)
                    ichimokuProtocol(_last_position);
                else if (_last_position.getProfitLossInPips() > _pnl_threshold)*/
                trailingStopProtocol(_last_position);
            }
        Calendar cal = Calendar.getInstance();
        Date lastDate = new Date(LastTick.getTime());
        cal.setTime(lastDate);
        if (cal.get(Calendar.DAY_OF_WEEK) == Calendar.FRIDAY &&
            cal.get(Calendar.HOUR_OF_DAY) > 17)
            CloseAllPositons();
    }

    private void ichimokuProtocol(IOrder order){
        if ((order.isLong() && ichimokuCloseLongEvent()) || 
            (!order.isLong() && ichimokuCloseShortEvent())){
            this._last_position = order;
            ClosePosition();
            console.getOut().println("Ichimoku protocol activated, closing position...");
        }
    }

    private boolean ichimokuCloseLongEvent(){
        return (_senkouA > _senkouB && _tenkansen < _kijunsen ) ||
            _senkouA < _senkouB;
    }

    private boolean ichimokuCloseShortEvent(){
        return (_senkouA < _senkouB && _tenkansen > _kijunsen ) ||
            _senkouA > _senkouB;
    }

    private void trailingStopProtocol(IOrder order){
        double takeProfitTreshold = (order.isLong()) ? 
                round(order.getOpenPrice() + order.getInstrument().getPipValue() * dynamicTakeProfit, order.getInstrument()): 
                round(order.getOpenPrice() - order.getInstrument().getPipValue() * dynamicTakeProfit, order.getInstrument());
        
        if(!this._trailed && ((order.isLong() && (LastTick.getBid() > takeProfitTreshold)) ||
            (!order.isLong() && (LastTick.getAsk() < takeProfitTreshold))) ){
            updateTrailingStop(order);
        }
        //cciProtocol(order);
    }

    private void cciProtocol(IOrder order){
        if (cciEvent(order)){
            console.getOut().println("CCI Protocol activated, closing position...");
            this._last_position = order;
            ClosePosition();
        }
    }

    private boolean cciEvent(IOrder order){
        return (order.isLong() && (_cci < _shifted_cci || (_cci > _shifted_cci && _stoch_K < _stoch_D))) 
            || 
            (!order.isLong() && (_cci > _shifted_cci || (_cci < _shifted_cci && _stoch_K > _stoch_D))); 
    }

    private  void MarketOrder(String dir) {
        lotControl();
        ITick tick = getLastTick(defaultInstrument);

        IEngine.OrderCommand command = (dir=="BUY") ? IEngine.OrderCommand.BUY :
            IEngine.OrderCommand.SELL; 

        double stopLoss =  (dir=="BUY") ? tick.getBid() - defaultInstrument.getPipValue() * defaultStopLoss : 
                            tick.getBid() + defaultInstrument.getPipValue() * defaultStopLoss;
        double takeProfit = (dir=="BUY") ? round(tick.getBid() + defaultInstrument.getPipValue() * defaultTakeProfit, defaultInstrument):
                                round(tick.getBid() - defaultInstrument.getPipValue() * defaultTakeProfit, defaultInstrument);

        console.getOut().println("new Market Order: stopLoss = "+String.valueOf(stopLoss)+" | takeProfit= "+String.valueOf(takeProfit));
        try {
           _last_position = context.getEngine().submitOrder(getLabel(), 
                   defaultInstrument, command, _lot, 0, defaultSlippage,
                   stopLoss, takeProfit, 0, "");
        } catch (JFException e) {
            e.printStackTrace();
        }
    }

    private  void updateTrailingStop(IOrder order) {
        _pips_trailing_step = (_pips_trailing_step < 10) ? 10 : _pips_trailing_step;
        if (_pips_trailing_step != order.getTrailingStep()) {
            double stopLoss;
            try {
                stopLoss = (order.isLong()) ? 
                round(order.getOpenPrice() + order.getInstrument().getPipValue() * dynamicTakeProfit/2, order.getInstrument()): 
                round(order.getOpenPrice() - order.getInstrument().getPipValue() * dynamicTakeProfit/2, order.getInstrument());;
                //stopLoss =  (order.isLong()) ? 
                //round(order.getOpenPrice() - order.getInstrument().getPipValue() * dynamicStopLoss, order.getInstrument()): 
                //round(order.getOpenPrice() + order.getInstrument().getPipValue() * (dynamicStopLoss), order.getInstrument());
                //console.getOut().println("Condition for trailing: " + stopLoss + " != " + order.getStopLossPrice());
                console.getOut().println("Trailing stop setting...");
                if ((stopLoss != order.getStopLossPrice()) && (stopLoss != 0)
                    && (order.getState().equals(IOrder.State.OPENED) ||
                        order.getState().equals(IOrder.State.FILLED))) {

                    order.setStopLossPrice(stopLoss, OfferSide.BID, _pips_trailing_step);
                    this._trailed = true;
                }

                TradeEventAction event = new TradeEventAction();
                event.setMessageType(IMessage.Type.ORDER_CHANGED_OK);
                event.setPositionLabel(order.getLabel());
                tradeEventActions.add(event);
                
            } catch (JFException e) {
                e.printStackTrace();
            }
        }
    }

    private void ClosePosition(){
        try {
            if (_last_position != null && (_last_position.getState() == IOrder.State.OPENED ||
                _last_position.getState() == IOrder.State.FILLED))
                
                _last_position.close();
            
        } catch (JFException e)  {
            e.printStackTrace();
        }
    }

    private void CloseAllPositons(){
        for (IOrder position : OpenPositions){
            if (position.getState() == IOrder.State.OPENED ||
                position.getState() == IOrder.State.FILLED)
                _last_position = position;
            ClosePosition();
        }
    }

    private void createStoch() {
        OfferSide[] offerside = new OfferSide[1];
        IIndicators.AppliedPrice[] appliedPrice = new IIndicators.AppliedPrice[1];
        offerside[0] = OfferSide.BID;
        appliedPrice[0] = IIndicators.AppliedPrice.CLOSE;
        Object[] params = new Object[5];
        params[0] = _stoch_fk_period;
        params[1] = _stoch_sk_period;
        params[2] = 0;// K -> SMA
        params[3] = _stoch_sd_period;
        params[4] = 0;// D -> SMA
        try {
            subscriptionInstrumentCheck(defaultInstrument);
            long time = context.getHistory().getBar(defaultInstrument, _stoch_period, OfferSide.BID, _stoch_shift).getTime();
            Object[] indicatorResult = context.getIndicators().calculateIndicator(defaultInstrument, _stoch_period, offerside,
                    "STOCH", appliedPrice, params, Filter.WEEKENDS, 1, time, 0);
            if ((new Double(((double [])indicatorResult[0])[0])) == null)
                this._stoch_K = Double.NaN;
            else
                this._stoch_K = (((double [])indicatorResult[0])[0]);
            if ((new Double(((double [])indicatorResult[1])[0])) == null)
                this._stoch_D = Double.NaN;
            else
                this._stoch_D = (((double [])indicatorResult[1])[0]);
        } catch (JFException e) {
            e.printStackTrace();
            console.getErr().println(e);
            this._stoch_K = Double.NaN;
            this._stoch_D = Double.NaN;
        }
    }

    private void createStochF() {
        OfferSide[] offerside = new OfferSide[1];
        IIndicators.AppliedPrice[] appliedPrice = new IIndicators.AppliedPrice[1];
        offerside[0] = OfferSide.BID;
        appliedPrice[0] = IIndicators.AppliedPrice.CLOSE;
        Object[] params = new Object[3];
        params[0] = _stoch_fk_period;
        params[1] = _stoch_sk_period;
        params[2] = 0;
        try {
            subscriptionInstrumentCheck(defaultInstrument);
            long time = context.getHistory().getBar(defaultInstrument, _stochf_period, OfferSide.BID, _stoch_shift).getTime();
            Object[] indicatorResult = context.getIndicators().calculateIndicator(defaultInstrument, _stochf_period, offerside,
                    "STOCHF", appliedPrice, params, Filter.WEEKENDS, 1, time, 0);
            if ((new Double(((double [])indicatorResult[0])[0])) == null) 
                this._stochf_K = Double.NaN;
            else
                this._stochf_K = (((double [])indicatorResult[0])[0]);  
            if ((new Double(((double [])indicatorResult[1])[0])) == null) 
                this._stochf_D = Double.NaN;
            else
                this._stochf_D = (((double [])indicatorResult[1])[0]);
        } catch (JFException e) {
            e.printStackTrace();
            console.getErr().println(e);
            this._stochf_K = Double.NaN;
            this._stochf_D = Double.NaN;
        }
    }

    private void createShiftedStochF() {
        OfferSide[] offerside = new OfferSide[1];
        IIndicators.AppliedPrice[] appliedPrice = new IIndicators.AppliedPrice[1];
        offerside[0] = OfferSide.BID;
        appliedPrice[0] = IIndicators.AppliedPrice.CLOSE;
        Object[] params = new Object[3];
        params[0] = _stoch_fk_period;
        params[1] = _stoch_sk_period;
        params[2] = 0;
        try {
            subscriptionInstrumentCheck(defaultInstrument);
            long time = context.getHistory().getBar(defaultInstrument, _stochf_period, OfferSide.BID, _stochf_shifted_shift).getTime();
            Object[] indicatorResult = context.getIndicators().calculateIndicator(defaultInstrument, _stochf_period, offerside,
                    "STOCHF", appliedPrice, params, Filter.WEEKENDS, 1, time, 0);
            if ((new Double(((double [])indicatorResult[0])[0])) == null) {
                this._stochf_shifted_K = Double.NaN;
            } else { 
                this._stochf_shifted_K = (((double [])indicatorResult[0])[0]);
            } 
            if ((new Double(((double [])indicatorResult[1])[0])) == null) {
                this._stochf_shifted_D = Double.NaN;
            } else { 
                this._stochf_shifted_D = (((double [])indicatorResult[1])[0]);
            } 
        } catch (JFException e) {
            e.printStackTrace();
            console.getErr().println(e);
            this._stochf_shifted_K = Double.NaN;
            this._stochf_shifted_D = Double.NaN;
        }
    }

    private void createCCI() {
        OfferSide[] offerside = new OfferSide[1];
        IIndicators.AppliedPrice[] appliedPrice = new IIndicators.AppliedPrice[1];
        offerside[0] = OfferSide.BID;
        appliedPrice[0] = IIndicators.AppliedPrice.CLOSE;
        Object[] params = new Object[1];
        params[0] = _cci_time_period;
        try {
            subscriptionInstrumentCheck(defaultInstrument);
            long time = context.getHistory().getBar(defaultInstrument, _cci_period, OfferSide.BID, _cci_shift).getTime();
            Object[] indicatorResult = context.getIndicators().calculateIndicator(defaultInstrument, _cci_period, offerside,
                    "CCI", appliedPrice, params, Filter.WEEKENDS, 1, time, 0);
            if ((new Double(((double [])indicatorResult[0])[0])) == null)
                this._cci = Double.NaN;
            else
                this._cci = (((double [])indicatorResult[0])[0]);
        } catch (JFException e) {
            e.printStackTrace();
            console.getErr().println(e);
            this._cci = Double.NaN;
        }
    }

    private void createShiftedCCI() {
        OfferSide[] offerside = new OfferSide[1];
        IIndicators.AppliedPrice[] appliedPrice = new IIndicators.AppliedPrice[1];
        offerside[0] = OfferSide.BID;
        appliedPrice[0] = IIndicators.AppliedPrice.CLOSE;
        Object[] params = new Object[1];
        params[0] = _cci_time_period;
        try {
            subscriptionInstrumentCheck(defaultInstrument);
            long time = context.getHistory().getBar(defaultInstrument, _cci_period, OfferSide.BID, _cci_shifted_shift).getTime();
            Object[] indicatorResult = context.getIndicators().calculateIndicator(defaultInstrument, _cci_period, offerside,
                    "CCI", appliedPrice, params, Filter.WEEKENDS, 1, time, 0);
            if ((new Double(((double [])indicatorResult[0])[0])) == null) 
                this._shifted_cci= Double.NaN;
            else
                this._shifted_cci = (((double [])indicatorResult[0])[0]);
        } catch (JFException e) {
            e.printStackTrace();
            console.getErr().println(e);
            this._shifted_cci = Double.NaN;
        }
    }

    private void createSAR() {
        OfferSide[] offerside = new OfferSide[1];
        IIndicators.AppliedPrice[] appliedPrice = new IIndicators.AppliedPrice[1];
        offerside[0] = OfferSide.BID;
        appliedPrice[0] = IIndicators.AppliedPrice.CLOSE;
        Object[] params = new Object[2];
        params[0] = _sar_acc;
        params[1] = _sar_max;
        try {
            subscriptionInstrumentCheck(defaultInstrument);
            long time = context.getHistory().getBar(defaultInstrument, _sar_period, OfferSide.BID, _sar_shift).getTime();
            Object[] indicatorResult = context.getIndicators().calculateIndicator(defaultInstrument, _sar_period, offerside,
                    "SAR", appliedPrice, params, Filter.WEEKENDS, 1, time, 0);
            if ((new Double(((double [])indicatorResult[0])[0])) == null) 
                this._sar = Double.NaN;
            else 
                this._sar = (((double [])indicatorResult[0])[0]);
        } catch (JFException e) {
            e.printStackTrace();
            console.getErr().println(e);
            this._sar = Double.NaN;
        }
    }

    private void createIchimoku() {
        OfferSide[] offerside = new OfferSide[1];
        IIndicators.AppliedPrice[] appliedPrice = new IIndicators.AppliedPrice[1];
        offerside[0] = OfferSide.BID;
        appliedPrice[0] = IIndicators.AppliedPrice.CLOSE;
        Object[] params = new Object[3];
        params[0] = _ichimoku_tenkansen;
        params[1] = _ichimoku_kijunsen;
        params[2] = _ichimoku_senkou;
        try {
            subscriptionInstrumentCheck(defaultInstrument);
            long time = context.getHistory().getBar(defaultInstrument, _ichimoku_period, OfferSide.BID, _ichimoku_shift).getTime();
            Object[] indicatorResult = context.getIndicators().calculateIndicator(defaultInstrument, _ichimoku_period, offerside,
                    "ICHIMOKUVF", appliedPrice, params, Filter.WEEKENDS, 1, time, 0);
            if ((new Double(((double [])indicatorResult[0])[0])) == null)
                this._tenkansen = Double.NaN;
            else 
                this._tenkansen = (((double [])indicatorResult[0])[0]);
            if ((new Double(((double [])indicatorResult[1])[0])) == null)
                this._kijunsen = Double.NaN;
            else 
                this._kijunsen = (((double [])indicatorResult[1])[0]);
            if ((new Double(((double [])indicatorResult[2])[0])) == null)
                this._chinkou = Double.NaN;
            else 
                this._chinkou = (((double [])indicatorResult[2])[0]);
            if ((new Double(((double [])indicatorResult[3])[0])) == null)
                this._senkouA = Double.NaN;
            else 
                this._senkouA = (((double [])indicatorResult[3])[0]);
            if ((new Double(((double [])indicatorResult[4])[0])) == null)
                this._senkouB = Double.NaN;
            else
                this._senkouB = (((double [])indicatorResult[4])[0]);
        } catch (JFException e) {
            e.printStackTrace();
            console.getErr().println(e);
            this._tenkansen = Double.NaN;
            this._kijunsen = Double.NaN;
            this._chinkou = Double.NaN;
            this._senkouA = Double.NaN;
            this._senkouB = Double.NaN;
        }
    }

    private void createShiftedIchimoku() {
        OfferSide[] offerside = new OfferSide[1];
        IIndicators.AppliedPrice[] appliedPrice = new IIndicators.AppliedPrice[1];
        offerside[0] = OfferSide.BID;
        appliedPrice[0] = IIndicators.AppliedPrice.CLOSE;
        Object[] params = new Object[3];
        params[0] = _ichimoku_tenkansen;
        params[1] = _ichimoku_kijunsen;
        params[2] = _ichimoku_senkou;
        try {
            subscriptionInstrumentCheck(defaultInstrument);
            long time = context.getHistory().getBar(defaultInstrument, _ichimoku_period, OfferSide.BID, _ichimoku_shifted_shift).getTime();
            Object[] indicatorResult = context.getIndicators().calculateIndicator(defaultInstrument, _ichimoku_period, offerside,
                    "ICHIMOKUVF", appliedPrice, params, Filter.WEEKENDS, 1, time, 0);
            if ((new Double(((double [])indicatorResult[0])[0])) == null) 
                this._tenkansen_shifted = Double.NaN;
            else 
                this._tenkansen_shifted = (((double [])indicatorResult[0])[0]);
            
            if ((new Double(((double [])indicatorResult[1])[0])) == null) 
                this._kijunsen_shifted = Double.NaN;
            else 
                this._kijunsen_shifted = (((double [])indicatorResult[1])[0]);
            
            if ((new Double(((double [])indicatorResult[2])[0])) == null) 
                this._chinkou_shifted = Double.NaN;
            else 
                this._chinkou_shifted = (((double [])indicatorResult[2])[0]);
            
            if ((new Double(((double [])indicatorResult[3])[0])) == null) 
                this._senkouA_shifted = Double.NaN;
            else 
                this._senkouA_shifted = (((double [])indicatorResult[3])[0]);
            
            if ((new Double(((double [])indicatorResult[4])[0])) == null) 
                this._senkouB_shifted = Double.NaN;
            else 
                this._senkouB_shifted = (((double [])indicatorResult[4])[0]);
        } catch (JFException e) {
            e.printStackTrace();
            console.getErr().println(e);
            this._tenkansen_shifted = Double.NaN;
            this._kijunsen_shifted = Double.NaN;
            this._chinkou_shifted = Double.NaN;
            this._senkouA_shifted = Double.NaN;
            this._senkouB_shifted = Double.NaN;
        }
    }        

    class Candle  {

        IBar bar;
        Period period;
        Instrument instrument;
        OfferSide offerSide;

        public Candle(IBar bar, Period period, Instrument instrument, OfferSide offerSide) {
            this.bar = bar;
            this.period = period;
            this.instrument = instrument;
            this.offerSide = offerSide;
        }

        public Period getPeriod() {
            return period;
        }

        public void setPeriod(Period period) {
            this.period = period;
        }

        public Instrument getInstrument() {
            return instrument;
        }

        public void setInstrument(Instrument instrument) {
            this.instrument = instrument;
        }

        public OfferSide getOfferSide() {
            return offerSide;
        }

        public void setOfferSide(OfferSide offerSide) {
            this.offerSide = offerSide;
        }

        public IBar getBar() {
            return bar;
        }

        public void setBar(IBar bar) {
            this.bar = bar;
        }

        public long getTime() {
            return bar.getTime();
        }

        public double getOpen() {
            return bar.getOpen();
        }

        public double getClose() {
            return bar.getClose();
        }

        public double getLow() {
            return bar.getLow();
        }

        public double getHigh() {
            return bar.getHigh();
        }

        public double getVolume() {
            return bar.getVolume();
        }
    }
    
    class Tick {

        private ITick tick;
        private Instrument instrument;

        public Tick(ITick tick, Instrument instrument){
            this.instrument = instrument;
            this.tick = tick;
        }

        public Instrument getInstrument(){
           return  instrument;
        }

        public double getAsk(){
           return  tick.getAsk();
        }

        public double getBid(){
           return  tick.getBid();
        }

        public double getAskVolume(){
           return  tick.getAskVolume();
        }

        public double getBidVolume(){
            return tick.getBidVolume();
        }

        public long getTime(){
           return  tick.getTime();
        }

        public ITick getTick(){
           return  tick;
        }
    }

    protected String getLabel() {
        String label;
        label = "IVF" + getCurrentTime(LastTick.getTime()) + generateRandom(10000) + generateRandom(10000);
        return label;
    }

    private String getCurrentTime(long time) {
        SimpleDateFormat sdf = new SimpleDateFormat(DATE_FORMAT_NOW);
        return sdf.format(time);
    }

    private static String generateRandom(int n) {
        int randomNumber = (int) (Math.random() * n);
        String answer = "" + randomNumber;
        if (answer.length() > 3) {
            answer = answer.substring(0, 4);
        }
        return answer;
    }

    class TradeEventAction {
        private IMessage.Type messageType;
        private String nextBlockId = "";
        private String positionLabel = "";
        private int flowId = 0;

        public IMessage.Type getMessageType() {
            return messageType;
        }

        public void setMessageType(IMessage.Type messageType) {
            this.messageType = messageType;
        }

        public String getNextBlockId() {
            return nextBlockId;
        }

        public void setNextBlockId(String nextBlockId) {
            this.nextBlockId = nextBlockId;
        }
        public String getPositionLabel() {
            return positionLabel;
       }

        public void setPositionLabel(String positionLabel) {
            this.positionLabel = positionLabel;
        }
        public int getFlowId() {
            return flowId;
        }
        public void setFlowId(int flowId) {
            this.flowId = flowId;
        }
    }
}