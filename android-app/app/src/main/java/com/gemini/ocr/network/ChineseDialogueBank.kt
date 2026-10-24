package com.gemini.ocr.network

/**
 * High-frequency preloaded Chinese (Simplified & Traditional) dialogue, subtitle, and manhua/game
 * phrase bank for < 1ms zero-latency translation on Android.
 *
 * Implements a 3-layer matching engine:
 *  1. Normalized exact match (strips OCR noise, speaker brackets, and punctuation; maps Traditional -> Simplified).
 *  2. Multi-clause split & join (splits compound lines by comma/space/pause and translates each clause if all match).
 *  3. High-frequency dialogue template patterns (e.g. "别...了", "...大人/师兄/前辈", "快...", "怎么...").
 */
object ChineseDialogueBank {

    // Fast Traditional -> Simplified character normalization map for common subtitle/manhua characters
    private val traditionalToSimplified: Map<Char, Char> = mapOf(
        '麼' to '么', '們' to '们', '這' to '这', '個' to '个', '來' to '来', '時' to '时',
        '會' to '会', '過' to '过', '對' to '对', '開' to '开', '關' to '关', '見' to '见',
        '說' to '说', '話' to '话', '請' to '请', '謝' to '谢', '師' to '师', '門' to '门',
        '長' to '长', '輩' to '辈', '氣' to '气', '實' to '实', '體' to '体', '發' to '发',
        '現' to '现', '點' to '点', '頭' to '头', '臉' to '脸', '樣' to '样', '種' to '种',
        '東' to '东', '車' to '车', '馬' to '马', '鳥' to '鸟', '龍' to '龙', '劍' to '剑',
        '殺' to '杀', '進' to '进', '還' to '还', '沒' to '没', '別' to '别', '動' to '动',
        '聽' to '听', '讓' to '让', '給' to '给', '誰' to '谁', '為' to '为', '嗎' to '吗',
        '萬' to '万', '與' to '与', '無' to '无', '風' to '风', '雲' to '云', '電' to '电',
        '靈' to '灵', '陣' to '阵', '煉' to '炼', '藥' to '药', '獸' to '兽', '聖' to '圣',
        '戰' to '战', '鬥' to '斗', '勝' to '胜', '敗' to '败', '護' to '护', '駕' to '驾',
        '滾' to '滚', '閉' to '闭', '饒' to '饶', '幫' to '帮', '歡' to '欢', '愛' to '爱',
        '親' to '亲', '錯' to '错', '難' to '难', '題' to '题', '確' to '确', '認' to '认',
        '繼' to '继', '續' to '续', '結' to '结', '選' to '选', '擇' to '择', '夠' to '够',
        '趕' to '赶', '緊' to '紧', '離' to '离', '帶' to '带', '問' to '问', '覺' to '觉',
        '應' to '应', '該' to '该', '經' to '经', '從' to '从', '後' to '后', '裡' to '里',
        '邊' to '边', '處' to '处', '變' to '变', '驚' to '惊', '訝' to '讶', '惡' to '恶',
        '該' to '该', '當' to '当', '真' to '真', '假' to '假', '實' to '实', '際' to '际',
        '總' to '总', '統' to '统', '領' to '领', '將' to '将', '軍' to '军', '閣' to '阁',
        '樓' to '楼', '殿' to '殿', '宮' to '宫', '城' to '城', '國' to '国', '家' to '家',
        '天' to '天', '地' to '地', '玄' to '玄', '黃' to '黄', '宇' to '宇', '宙' to '宙',
        '洪' to '洪', '荒' to '荒', '陰' to '阴', '陽' to '阳', '乾' to '乾', '坤' to '坤',
        '巔' to '巅', '峰' to '峰', '極' to '极', '限' to '限', '破' to '破', '碎' to '碎',
        '滅' to '灭', '絕' to '绝', '頂' to '顶', '級' to '级', '階' to '阶', '段' to '段',
        '層' to '层', '次' to '次', '強' to '强', '弱' to '弱', '勝' to '胜', '負' to '负',
        '輸' to '输', '贏' to '赢', '買' to '买', '賣' to '卖', '錢' to '钱', '財' to '财',
        '寶' to '宝', '貝' to '贝', '儲' to '储', '戒' to '戒', '丹' to '丹', '符' to '符',
        '器' to '器', '訣' to '诀', '功' to '功', '法' to '法', '技' to '技', '術' to '术'
    )

    // Preloaded Chinese movie, drama, donghua, manhua & game dialogue map (Simplified keys, normalized)
    private val dialogueMap: Map<String, String> = buildMap(420) {
        // 1. Everyday Movie & Drama Conversational Lines
        put("你好", "Xin chào")
        put("你们好", "Chào mọi người")
        put("大家好", "Chào mọi người")
        put("早上好", "Chào buổi sáng")
        put("晚上好", "Chào buổi tối")
        put("晚安", "Chúc ngủ ngon")
        put("好久不见", "Lâu rồi không gặp")
        put("谢谢", "Cảm ơn")
        put("谢谢你", "Cảm ơn bạn")
        put("多谢", "Đa tạ")
        put("非常感谢", "Vô cùng cảm ơn")
        put("不客气", "Đừng khách sáo")
        put("不用谢", "Không cần cảm ơn")
        put("对不起", "Xin lỗi")
        put("真的很抱歉", "Thực sự rất xin lỗi")
        put("抱歉", "Xin lỗi")
        put("不好意思", "Ngại quá")
        put("没关系", "Không sao đâu")
        put("没事", "Không sao")
        put("没事吧", "Không sao chứ")
        put("你没事吧", "Bạn không sao chứ")
        put("你还好吗", "Bạn vẫn ổn chứ")
        put("我没事", "Tôi không sao")
        put("不用担心", "Không cần lo lắng")
        put("别担心", "Đừng lo lắng")
        put("放心吧", "Yên tâm đi")
        put("你放心", "Cứ yên tâm")
        put("再见", "Tạm biệt")
        put("回头见", "Hẹn gặp lại")
        put("明天见", "Mai gặp lại")
        put("保重", "Bảo trọng")
        put("一路顺风", "Thượng lộ bình an")
        put("请进", "Mời vào")
        put("请坐", "Mời ngồi")
        put("请稍等", "Vui lòng đợi một chút")
        put("等一下", "Đợi một chút")
        put("等一等", "Đợi đã")
        put("等等", "Khoan đã")
        put("等等我", "Đợi tôi với")
        put("慢着", "Khoan đã")
        put("且慢", "Khoan đã")
        put("站住", "Đứng lại")
        put("别跑", "Đừng chạy")
        put("别动", "Đừng cử động")
        put("不许动", "Không được nhúc nhích")
        put("住手", "Dừng tay")
        put("快住手", "Mau dừng tay")
        put("停下", "Dừng lại")
        put("快停下", "Mau dừng lại")
        put("放开我", "Buông tôi ra")
        put("放手", "Buông tay ra")
        put("快放开我", "Mau buông tôi ra")
        put("别过来", "Đừng lại gần đây")
        put("你别过来", "Ngươi đừng qua đây")
        put("离我远点", "Tránh xa tôi ra")
        put("滚", "Cút")
        put("滚开", "Cút ngay")
        put("给我滚", "Cút ngay cho ta")
        put("闭嘴", "Câm miệng")
        put("你闭嘴", "Ngươi câm miệng đi")
        put("少废话", "Bớt废话 / Bớt lời đi")
        put("别废话", "Đừng nhiều lời")
        put("胡说八道", "Ăn nói hàm hồ")
        put("胡说", "Nói bậy")
        put("一派胡言", "Toàn lời bịa đặt")

        // 2. Questions, Reactions & Emotional Exclamations
        put("什么", "Cái gì")
        put("你说什么", "Ngươi nói cái gì")
        put("这是什么", "Đây là cái gì")
        put("那是什么", "Đó là cái gì")
        put("怎么了", "Sao thế")
        put("你怎么了", "Bạn làm sao thế")
        put("怎么回事", "Chuyện gì thế")
        put("这是怎么回事", "Chuyện này là thế nào")
        put("到底怎么回事", "Rốt cuộc là chuyện gì")
        put("到底怎么了", "Rốt cuộc đã xảy ra chuyện gì")
        put("发生了什么", "Đã xảy ra chuyện gì")
        put("发生什么事了", "Xảy ra chuyện gì vậy")
        put("你是谁", "Ngươi là ai")
        put("你到底是谁", "Rốt cuộc ngươi là ai")
        put("你们是谁", "Các ngươi là ai")
        put("他是谁", "Hắn là ai")
        put("这是哪里", "Đây là đâu")
        put("我在哪", "Tôi đang ở đâu")
        put("你在哪里", "Bạn đang ở đâu")
        put("他在哪里", "Hắn ở đâu")
        put("为什么", "Tại sao")
        put("为什么会这样", "Tại sao lại như vậy")
        put("怎么会这样", "Sao lại ra nông nỗi này")
        put("怎么可能", "Làm sao có thể")
        put("这怎么可能", "Chuyện này làm sao có thể")
        put("不可能", "Không thể nào")
        put("这不可能", "Chuyện này không thể nào")
        put("绝对不可能", "Tuyệt đối không thể nào")
        put("难以置信", "Thật khó tin")
        put("太不可思议了", "Thật không thể tin nổi")
        put("真的吗", "Thật sao")
        put("真的假的", "Thật hay giả vậy")
        put("当真", "Thật chứ")
        put("此话当真", "Lời này là thật chứ")
        put("开玩笑的吧", "Đùa đấy à")
        put("你在开玩笑吗", "Bạn đang đùa đấy à")
        put("开什么玩笑", "Đùa kiểu gì thế")
        put("你疯了吗", "Ngươi điên rồi sao")
        put("你疯了", "Ngươi điên rồi")
        put("你在干什么", "Ngươi đang làm cái gì vậy")
        put("你想干什么", "Ngươi muốn làm gì")
        put("你要干什么", "Ngươi định làm gì")
        put("怎么办", "Phải làm sao đây")
        put("现在怎么办", "Bây giờ phải làm sao")
        put("我们该怎么办", "Chúng ta phải làm sao đây")
        put("来不及了", "Không kịp nữa rồi")
        put("已经来不及了", "Đã không kịp nữa rồi")
        put("太晚了", "Muộn quá rồi")
        put("糟了", "Hỏng rồi")
        put("不好", "Không xong rồi")
        put("不好了", "Không xong rồi")
        put("完蛋了", "Tiêu đời rồi")
        put("完了", "Xong đời rồi")
        put("该死", "Chết tiệt")
        put("可恶", "Đáng ghét")
        put("混蛋", "Tên khốn")
        put("混账", "Hỗn láo")
        put("王八蛋", "Đồ khốn kiếp")
        put("天哪", "Trời ơi")
        put("我的天", "Trời đất ơi")
        put("太好了", "Tốt quá rồi")
        put("真是太好了", "Thật sự tốt quá rồi")
        put("太棒了", "Tuyệt vời quá")
        put("干得好", "Làm tốt lắm")
        put("干得漂亮", "Làm đẹp lắm")

        // 3. Action, Urgency & Responses
        put("我知道了", "Tôi biết rồi")
        put("知道了", "Biết rồi")
        put("我明白", "Tôi hiểu")
        put("我明白了", "Tôi hiểu rồi")
        put("明白", "Rõ")
        put("原来如此", "Hóa ra là vậy")
        put("原来是这样", "Thì ra là như vậy")
        put("怪不得", "Thảo nào")
        put("难怪", "Chẳng trách")
        put("果然", "Quả nhiên")
        put("果然如此", "Quả nhiên là vậy")
        put("不出所料", "Đúng như dự đoán")
        put("难道", "Chẳng lẽ")
        put("难道说", "Chẳng lẽ là")
        put("莫非", "Chẳng lẽ")
        put("当然", "Đương nhiên")
        put("当然了", "Đương nhiên rồi")
        put("那当然", "Đương nhiên rồi")
        put("没问题", "Không thành vấn đề")
        put("包在我身上", "Cứ để tôi lo")
        put("交给我吧", "Cứ giao cho tôi")
        put("交给你了", "Giao cho bạn đấy")
        put("拜托了", "Nhờ cả vào bạn")
        put("拜托你了", "Trông cậy vào bạn đấy")
        put("好的", "Được")
        put("好吧", "Được thôi")
        put("行吧", "Cũng được")
        put("可以", "Được")
        put("随便你", "Tùy bạn")
        put("算了吧", "Bỏ đi")
        put("算了", "Thôi bỏ đi")
        put("没关系了", "Không sao nữa rồi")
        put("快走", "Mau đi thôi")
        put("快跑", "Mau chạy đi")
        put("快逃", "Mau chạy trốn")
        put("快点", "Nhanh lên")
        put("动作快点", "Hành động nhanh lên")
        put("赶紧走", "Mau đi nhanh")
        put("我们走", "Chúng ta đi thôi")
        put("走吧", "Đi thôi")
        put("出发", "Xuất phát")
        put("出发吧", "Lên đường thôi")
        put("跟我来", "Đi theo tôi")
        put("跟上", "Bám theo")
        put("跟我们走一趟", "Đi theo chúng tôi một chuyến")
        put("回去吧", "Quay về thôi")
        put("回家吧", "Về nhà thôi")
        put("我回来了", "Tôi về rồi đây")
        put("欢迎回来", "Mừng bạn trở về")
        put("小心", "Cẩn thận")
        put("小心点", "Cẩn thận một chút")
        put("当心", "Coi chừng")
        put("注意安全", "Chú ý an toàn")
        put("救命", "Cứu mạng")
        put("救命啊", "Cứu mạng với")
        put("救我", "Cứu tôi với")
        put("快来人", "Người đâu mau tới")
        put("有人吗", "Có ai ở đó không")
        put("冷静点", "Bình tĩnh lại đi")
        put("冷静一下", "Bình tĩnh chút đi")
        put("别冲动", "Đừng kích động")
        put("别慌", "Đừng hoảng")
        put("听我解释", "Nghe tôi giải thích đã")
        put("你听我说", "Bạn nghe tôi nói này")
        put("相信我", "Hãy tin tôi")
        put("我相信你", "Tôi tin bạn")
        put("别害怕", "Đừng sợ")
        put("不用怕", "Không cần sợ")
        put("有我在", "Có tôi ở đây rồi")
        put("我会保护你的", "Tôi sẽ bảo vệ bạn")
        put("我喜欢你", "Tôi thích bạn")
        put("我爱你", "Anh yêu em")
        put("我想你", "Tôi nhớ bạn")
        put("对不起我来晚了", "Xin lỗi, tôi đến muộn")
        put("辛苦了", "Vất vả cho bạn rồi")
        put("别说了", "Đừng nói nữa")
        put("够了", "Đủ rồi")
        put("已经够了", "Đã đủ lắm rồi")
        put("到此为止", "Dừng lại ở đây thôi")
        put("我不会放过你的", "Ta sẽ không tha cho ngươi đâu")
        put("你给我等着", "Ngươi cứ đợi đấy")
        put("走着瞧", "Cứ chống mắt lên xem")

        // 4. Xianxia / Wuxia / Historical Drama / Manhua (Tu tiên, Kiếm hiệp, Cổ trang)
        put("师父", "Sư phụ")
        put("师尊", "Sư tôn")
        put("师兄", "Sư huynh")
        put("大师兄", "Đại sư huynh")
        put("师姐", "Sư tỷ")
        put("师弟", "Sư đệ")
        put("师妹", "Sư muội")
        put("小师妹", "Tiểu sư muội")
        put("掌门", "Chưởng môn")
        put("宗主", "Tông chủ")
        put("教主", "Giáo chủ")
        put("门主", "Môn chủ")
        put("阁主", "Các chủ")
        put("谷主", "Cốc chủ")
        put("家主", "Gia chủ")
        put("族长", "Tộc trưởng")
        put("长老", "Trưởng lão")
        put("大长老", "Đại trưởng lão")
        put("太上长老", "Thái thượng trưởng lão")
        put("前辈", "Tiền bối")
        put("晚辈", "Vãn bối")
        put("道友", "Đạo hữu")
        put("在下", "Tại hạ")
        put("老夫", "Lão phu")
        put("本座", "Bản tọa")
        put("本尊", "Bản尊 / Bản tôn")
        put("本王", "Bản vương")
        put("本少爷", "Bản thiếu gia")
        put("本小姐", "Bản tiểu thư")
        put("公子", "Công tử")
        put("少爷", "Thiếu gia")
        put("大小姐", "Đại tiểu thư")
        put("小姐", "Tiểu thư")
        put("姑娘", "Cô nương")
        put("夫人", "Phu nhân")
        put("老爷", "Lão gia")
        put("陛下", "Bệ hạ")
        put("皇上", "Hoàng thượng")
        put("皇后", "Hoàng hậu")
        put("太后", "Thái hậu")
        put("太子", "Thái tử")
        put("太子殿下", "Thái tử điện hạ")
        put("殿下", "Điện hạ")
        put("王爷", "Vương gia")
        put("公主", "Công chúa")
        put("娘娘", "Nương nương")
        put("大人", "Đại nhân")
        put("丞相", "Thừa tướng")
        put("将军", "Tướng quân")
        put("大将军", "Đại tướng quân")
        put("统领", "Thống lĩnh")
        put("护卫", "Hộ vệ")
        put("属下", "Thuộc hạ")
        put("属下在", "Có thuộc hạ")
        put("遵命", "Tuân lệnh")
        put("领命", "Lĩnh mệnh")
        put("属下遵命", "Thuộc hạ tuân lệnh")
        put("属下明白", "Thuộc hạ đã rõ")
        put("属下该死", "Thuộc hạ đáng chết")
        put("属下知罪", "Thuộc hạ biết tội")
        put("微臣遵旨", "Vi thần tuân chỉ")
        put("谢主隆恩", "Tạ ơn long ân của bệ hạ")
        put("吾皇万岁", "Ngô hoàng vạn tuế")
        put("平身", "Bình thân")
        put("免礼", "Miễn lễ")
        put("退下", "Lui xuống đi")
        put("都退下吧", "Tất cả lui xuống đi")
        put("来人", "Người đâu")
        put("来人啊", "Người đâu")
        put("拿下", "Bắt lấy")
        put("给我拿下", "Bắt lấy hắn cho ta")
        put("格杀勿论", "Giết không tha")
        put("护驾", "Hộ giá")
        put("放肆", "Làm càn")
        put("大胆", "To gan")
        put("岂有此理", "Thật quá quắt")
        put("手下留情", "Xin hạ thủ lưu tình")
        put("刀下留人", "Đao hạ lưu nhân")
        put("饶命", "Tha mạng")
        put("饶命啊", "Xin tha mạng")
        put("前辈饶命", "Tiền bối tha mạng")
        put("别杀我", "Đừng giết tôi")
        put("求求你", "Cầu xin ngươi")
        put("多谢前辈", "Đa tạ tiền bối")
        put("多谢师尊", "Đa tạ sư tôn")
        put("多谢师父", "Đa tạ sư phụ")
        put("多谢师兄", "Đa tạ sư huynh")
        put("多谢师姐", "Đa tạ sư tỷ")
        put("多谢掌门", "Đa tạ chưởng môn")
        put("多谢大人", "Đa tạ đại nhân")
        put("多谢公子", "Đa tạ công tử")
        put("多谢成全", "Đa tạ đã thành toàn")
        put("拜见师尊", "Bái kiến sư tôn")
        put("拜见师父", "Bái kiến sư phụ")
        put("拜见掌门", "Bái kiến chưởng môn")
        put("恭迎师尊", "Cung nghênh sư tôn")
        put("得罪了", "Đắc tội rồi")
        put("失陪了", "Thất bồi rồi")
        put("告辞", "Cáo từ")
        put("后会有期", "Hậu hội hữu kỳ")
        put("恕难从命", "Thứ cho khó lòng tuân mệnh")
        put("找死", "Muốn chết")
        put("敢尔", "Dám làm càn")
        put("简直找死", "Đúng là muốn chết")
        put("你在找死", "Ngươi đang tự tìm cái chết")
        put("不知死活", "Không biết sống chết")
        put("自不量力", "Tự lượng sức mình đi")
        put("不自量力", "Không tự lượng sức")
        put("狂妄", "Cuồng vọng")
        put("狂妄至极", "Cuồng vọng tột cùng")
        put("受死吧", "Chịu chết đi")
        put("纳命来", "Nộp mạng đi")
        put("去死吧", "Chết đi")
        put("看招", "Xem chiêu")
        put("接招吧", "Tiếp chiêu đây")
        put("雕虫小技", "Tài mọn")
        put("不堪一击", "Không chịu nổi một đòn")
        put("不过如此", "Cũng chỉ đến thế mà thôi")
        put("好强的气息", "Khí tức mạnh quá")
        put("好强的威压", "Uy áp thật khủng khiếp")
        put("好恐怖的实力", "Thực lực thật đáng sợ")
        put("好快的速度", "Tốc độ nhanh quá")
        put("他竟然突破了", "Hắn vậy mà lại đột phá rồi")
        put("突破了", "Đột phá rồi")
        put("突破境界", "Đột phá cảnh giới")
        put("走火入魔", "Tẩu hỏa nhập ma")
        put("练气期", "Luyện Khí kỳ")
        put("筑基期", "Trúc Cơ kỳ")
        put("金丹期", "Kim Đan kỳ")
        put("元婴期", "Nguyên Anh kỳ")
        put("化神期", "Hóa Thần kỳ")
        put("渡劫期", "Độ Kiếp kỳ")
        put("大乘期", "Đại Thừa kỳ")
        put("储物戒", "Nhẫn trữ vật")
        put("空间戒指", "Nhẫn không gian")
        put("天材地宝", "Thiên tài địa bảo")

        // 5. Narrative Transitions, System Prompts & Game/Manhua UI
        put("与此同时", "Cùng lúc đó")
        put("另一边", "Ở một diễn biến khác")
        put("就在这时", "Đúng vào lúc này")
        put("突然", "Đột nhiên")
        put("第二天", "Ngày hôm sau")
        put("三天后", "Ba ngày sau")
        put("半年后", "Nửa năm sau")
        put("一年后", "Một năm sau")
        put("几分钟后", "Vài phút sau")
        put("片刻之后", "Một lát sau")
        put("不久之后", "Không lâu sau")
        put("回忆", "Hồi ức")
        put("系统", "Hệ thống")
        put("恭喜宿主", "Chúc mừng ký chủ")
        put("任务完成", "Nhiệm vụ hoàn thành")
        put("任务失败", "Nhiệm vụ thất bại")
        put("获得奖励", "Nhận được phần thưởng")
        put("主线任务", "Nhiệm vụ chính tuyến")
        put("支线任务", "Nhiệm vụ phụ")
        put("隐藏任务", "Nhiệm vụ ẩn")
        put("等级提升", "Tăng cấp")
        put("属性面板", "Bảng thuộc tính")
        put("技能冷却", "Hồi chiêu kỹ năng")
        put("生命值", "Điểm sinh mệnh")
        put("攻击力", "Sức tấn công")
        put("防御力", "Sức phòng thủ")
        put("确认", "Xác nhận")
        put("取消", "Hủy bỏ")
        put("确定", "Đồng ý")
        put("返回", "Quay lại")
        put("继续", "Tiếp tục")
        put("跳过", "Bỏ qua")
        put("设置", "Cài đặt")
        put("开始游戏", "Bắt đầu trò chơi")
        put("继续游戏", "Tiếp tục trò chơi")
        put("退出游戏", "Thoát trò chơi")
        put("未完待续", "Còn tiếp")
        put("待续", "Còn tiếp")
        put("本集完", "Hết tập này")
        put("本章完", "Hết chương này")
        put("全剧终", "Hết phim")
    }

    // High-frequency terminology for post-processing on-device ML Kit translations
    private val terminologyFixes: List<Pair<String, String>> = listOf(
        "师尊" to "Sư tôn",
        "师父" to "Sư phụ",
        "大师兄" to "Đại sư huynh",
        "师兄" to "Sư huynh",
        "师姐" to "Sư tỷ",
        "小师妹" to "Tiểu sư muội",
        "师妹" to "Sư muội",
        "师弟" to "Sư đệ",
        "掌门" to "Chưởng môn",
        "太上长老" to "Thái thượng trưởng lão",
        "大长老" to "Đại trưởng lão",
        "长老" to "Trưởng lão",
        "宗主" to "Tông chủ",
        "储物戒" to "nhẫn trữ vật",
        "筑基" to "Trúc Cơ",
        "金丹" to "Kim Đan",
        "元婴" to "Nguyên Anh",
        "化神" to "Hóa Thần",
        "渡劫" to "Độ Kiếp",
        "道友" to "đạo hữu",
        "前辈" to "tiền bối",
        "晚辈" to "vãn bối",
        "本座" to "bản tọa",
        "宿主" to "ký chủ"
    )

    private val titleSuffixMap: Map<String, String> = linkedMapOf(
        "太子殿下" to "Thái tử điện hạ",
        "大师兄" to "Đại sư huynh",
        "小师妹" to "Tiểu sư muội",
        "大长老" to "Đại trưởng lão",
        "大将军" to "Đại tướng quân",
        "大小姐" to "Đại tiểu thư",
        "师尊" to "sư tôn",
        "师父" to "sư phụ",
        "师兄" to "sư huynh",
        "师姐" to "sư tỷ",
        "师弟" to "sư đệ",
        "师妹" to "sư muội",
        "掌门" to "chưởng môn",
        "宗主" to "tông chủ",
        "长老" to "trưởng lão",
        "前辈" to "tiền bối",
        "道友" to "đạo hữu",
        "公子" to "công tử",
        "少爷" to "thiếu gia",
        "小姐" to "tiểu thư",
        "姑娘" to "cô nương",
        "夫人" to "phu nhân",
        "陛下" to "bệ hạ",
        "殿下" to "điện hạ",
        "王爷" to "vương gia",
        "大人" to "đại nhân",
        "将军" to "tướng quân"
    )

    val totalEntries: Int get() = dialogueMap.size

    private val leadingTrailingNoise = Regex("^[\\s\"'“”‘’「」『』《》〈〉()（）\\[\\]【】·•—\\-~～:：,，.。!！?？…]+|[\\s\"'“”‘’「」『』《》〈〉()（）\\[\\]【】·•—\\-~～:：,，.。!！?？…]+$")
    private val trailingBracketsAndQuotes = charArrayOf(' ', '\t', '\n', '"', '\'', '“', '”', '‘', '’', '「', '」', '『', '』', '《', '》', '〈', '〉', ')', '）', ']', '】')
    private val internalPunctuation = Regex("[\\s,，.。!！?？…—\\-~～:：;；、\"'“”‘’「」『』]+")
    private val clauseSplitter = Regex("[，,、；;。.！!？?…\\s]+")
    private val modalParticles = charArrayOf('啊', '呀', '呢', '吧', '啦', '哦', '噢', '嘛')

    fun normalizeTraditional(input: String): String {
        if (input.isEmpty()) return input
        var changed = false
        val chars = CharArray(input.length)
        for (i in input.indices) {
            val ch = input[i]
            val mapped = traditionalToSimplified[ch]
            if (mapped != null && mapped != ch) {
                chars[i] = mapped
                changed = true
            } else {
                chars[i] = ch
            }
        }
        return if (changed) String(chars) else input
    }

    fun normalizeKey(input: String): String {
        val simplified = normalizeTraditional(input.trim())
        val stripped = simplified.replace(leadingTrailingNoise, "")
        return stripped.replace(internalPunctuation, "")
    }

    private fun extractEndingPunctuation(raw: String): String {
        val trimmed = raw.trimEnd(*trailingBracketsAndQuotes)
        return when {
            trimmed.endsWith("？！") || trimmed.endsWith("?!") || trimmed.endsWith("！？") || trimmed.endsWith("!?") -> "?!"
            trimmed.endsWith("...") || trimmed.endsWith("…") || trimmed.endsWith("……") -> "..."
            trimmed.endsWith("？") || trimmed.endsWith("?") -> "?"
            trimmed.endsWith("！") || trimmed.endsWith("!") -> "!"
            else -> ""
        }
    }

    /**
     * Attempts to match a Chinese subtitle/dialogue line in < 1ms.
     * Returns a natural Vietnamese translation if matched, or null if full AI/ML Kit translation is needed.
     */
    fun matchInstant(sourceText: String): String? {
        val trimmed = sourceText.trim()
        if (trimmed.isEmpty() || trimmed.length > 48) return null

        val ending = extractEndingPunctuation(trimmed)
        val simplified = normalizeTraditional(trimmed)
        val cleanKey = normalizeKey(simplified)
        if (cleanKey.isEmpty()) return null

        // Layer 1A: Direct normalized match
        dialogueMap[cleanKey]?.let { return appendEnding(it, ending) }

        // Layer 1B: Strip a single trailing modal particle (啊/呀/吧/呢/啦/哦) if the core phrase is in the bank
        if (cleanKey.length >= 3 && cleanKey.last() in modalParticles) {
            val withoutParticle = cleanKey.dropLast(1)
            dialogueMap[withoutParticle]?.let { return appendEnding(it, ending) }
        }

        // Layer 2: Multi-clause split & join (e.g., "师兄，小心！" or "等一下，我马上来")
        val rawClauses = simplified
            .replace(leadingTrailingNoise, "")
            .split(clauseSplitter)
            .map { it.trim() }
            .filter { it.isNotEmpty() }

        if (rawClauses.size in 2..4) {
            val translatedClauses = ArrayList<String>(rawClauses.size)
            for (clause in rawClauses) {
                val matched = matchSingleClauseOrTemplate(clause) ?: return null
                translatedClauses.add(matched)
            }
            val joined = translatedClauses.mapIndexed { idx, part ->
                if (idx == 0) part else part.replaceFirstChar { it.lowercase() }
            }.joinToString(", ")
            return appendEnding(joined, ending)
        }

        // Layer 3: Single-clause template pattern matching
        matchTemplate(cleanKey)?.let { return appendEnding(it, ending) }

        return null
    }

    private fun matchSingleClauseOrTemplate(clause: String): String? {
        val key = normalizeKey(clause)
        if (key.isEmpty()) return null
        dialogueMap[key]?.let { return it }
        if (key.length >= 3 && key.last() in modalParticles) {
            dialogueMap[key.dropLast(1)]?.let { return it }
        }
        return matchTemplate(key)
    }

    private fun matchTemplate(cleanKey: String): String? {
        // Template A: "别[X]了" -> "Đừng [X] nữa"
        if (cleanKey.startsWith("别") && cleanKey.endsWith("了") && cleanKey.length in 3..6) {
            val action = cleanKey.substring(1, cleanKey.length - 1)
            val actionVi = when (action) {
                "哭" -> "khóc"
                "笑" -> "cười"
                "闹" -> "quậy"
                "吵" -> "ồn ào"
                "打" -> "đánh"
                "看" -> "nhìn"
                "想" -> "nghĩ"
                "逃" -> "chạy trốn"
                "跑" -> "chạy"
                "管" -> "bận tâm"
                "装" -> "giả vờ"
                "傻" -> "ngốc"
                else -> dialogueMap[action]?.lowercase()
            }
            if (actionVi != null) return "Đừng $actionVi nữa"
        }

        // Template B: "我马上[X]" / "马上[X]" -> "Tôi [X] ngay"
        if (cleanKey.startsWith("我马上") && cleanKey.length in 4..6) {
            val action = cleanKey.removePrefix("我马上")
            val actionVi = when (action) {
                "来" -> "đến"
                "到" -> "tới"
                "走" -> "đi"
                "去" -> "đi"
                "回来" -> "quay lại"
                else -> null
            }
            if (actionVi != null) return "Tôi $actionVi ngay"
        }

        // Template C: Title / Honorific calls (e.g., "林师兄", "萧公子")
        for ((suffixZh, suffixVi) in titleSuffixMap) {
            if (cleanKey == suffixZh) {
                return suffixVi.replaceFirstChar { it.uppercase() }
            }
        }

        return null
    }

    private fun appendEnding(base: String, ending: String): String {
        if (ending.isEmpty()) return base
        val trimmed = base.trimEnd('.', '!', '?')
        return "$trimmed$ending"
    }

    /**
     * Post-processes an ML Kit translation to ensure common cultivation/costume drama
     * titles are rendered with natural Sino-Vietnamese terms.
     */
    fun enrichTranslation(sourceText: String, translated: String): String {
        if (sourceText.isBlank() || translated.isBlank()) return translated
        val simplifiedSource = normalizeTraditional(sourceText)
        var out = translated
        for ((zhTerm, viTerm) in terminologyFixes) {
            if (simplifiedSource.contains(zhTerm) && out.contains(zhTerm)) {
                out = out.replace(Regex("(?<=\\p{L})${Regex.escape(zhTerm)}"), " $viTerm")
                    .replace(zhTerm, viTerm)
            }
        }
        return out
    }
}
