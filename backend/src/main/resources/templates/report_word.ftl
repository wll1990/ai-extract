<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<#-- ============================================================
     AI经验萃取平台 · Word报告 Freemarker 模板
     生成格式：OpenXML WordprocessingML (document.xml 部件)
     风格：专业培训手册 · A4纸打印
     配合 Apache POI + Freemarker 使用，最终打包为 .docx
     ============================================================ -->
<w:document
    xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main"
    xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships"
    xmlns:wp="http://schemas.openxmlformats.org/drawingml/2006/wordprocessingDrawing"
    xmlns:a="http://schemas.openxmlformats.org/drawingml/2006/main">

<w:body>

<#-- ============================
     全局样式定义
     ============================ -->
<w:sectPr>
    <w:pgSz w:w="11906" w:h="16838"/>  <#-- A4纸 210×297mm -->
    <w:pgMar w:top="1440" w:right="1440" w:bottom="1440" w:left="1440"
             w:header="720" w:footer="720" w:gutter="0"/>
    <w:headerReference r:id="rIdHeader" w:type="default"/>
    <w:footerReference r:id="rIdFooter" w:type="default"/>
</w:sectPr>

<#-- ============================
     封面
     ============================ -->

<#-- 顶部留白 -->
<w:p><w:pPr><w:spacing w:before="2400"/></w:pPr></w:p>

<#-- 企业名称，14pt 居中 -->
<w:p>
    <w:pPr><w:jc w:val="center"/></w:pPr>
    <w:r>
        <w:rPr><w:rFonts w:ascii="微软雅黑" w:eastAsia="微软雅黑"/>
               <w:sz w:val="28"/><w:szCs w:val="28"/>
               <w:color w:val="6B7280"/></w:rPr>
        <w:t xml:space="preserve">${companyName!""}</w:t>
    </w:r>
</w:p>

<#-- 中间留白 -->
<w:p><w:pPr><w:spacing w:before="600"/></w:pPr></w:p>

<#-- 报告名，36pt 深蓝 Bold 居中 -->
<w:p>
    <w:pPr><w:jc w:val="center"/></w:pPr>
    <w:r>
        <w:rPr><w:rFonts w:ascii="微软雅黑" w:eastAsia="微软雅黑"/>
               <w:b/><w:bCs/>
               <w:sz w:val="72"/><w:szCs w:val="72"/>
               <w:color w:val="1A2B4C"/></w:rPr>
        <w:t xml:space="preserve">《${title!""}》</w:t>
    </w:r>
</w:p>

<#-- 副标题，18pt 灰色居中 -->
<#if subtitle?? && subtitle != "">
<w:p>
    <w:pPr><w:jc w:val="center"/><w:spacing w:before="200"/></w:pPr>
    <w:r>
        <w:rPr><w:rFonts w:ascii="微软雅黑" w:eastAsia="微软雅黑"/>
               <w:sz w:val="36"/><w:szCs w:val="36"/>
               <w:color w:val="6B7280"/></w:rPr>
        <w:t xml:space="preserve">${subtitle}</w:t>
    </w:r>
</w:p>
</#if>

<#-- 金色分隔线 -->
<w:p>
    <w:pPr><w:jc w:val="center"/><w:spacing w:before="400" w:after="400"/>
           <w:pBdr><w:bottom w:val="single" w:sz="6" w:space="1" w:color="C8A45C"/></w:pBdr>
           <w:ind w:left="2400" w:right="2400"/></w:pPr>
</w:p>

<#-- 作者信息，14pt 黑色居中 -->
<w:p>
    <w:pPr><w:jc w:val="center"/><w:spacing w:before="200"/></w:pPr>
    <w:r>
        <w:rPr><w:rFonts w:ascii="微软雅黑" w:eastAsia="微软雅黑"/>
               <w:sz w:val="28"/><w:szCs w:val="28"/>
               <w:color w:val="1A1A1A"/></w:rPr>
        <w:t xml:space="preserve">作者：${authorName!""}</w:t>
    </w:r>
</w:p>

<#if authorTitle?? && authorTitle != "">
<w:p>
    <w:pPr><w:jc w:val="center"/></w:pPr>
    <w:r>
        <w:rPr><w:rFonts w:ascii="微软雅黑" w:eastAsia="微软雅黑"/>
               <w:sz w:val="28"/><w:szCs w:val="28"/>
               <w:color w:val="1A1A1A"/></w:rPr>
        <w:t xml:space="preserve">岗位：${authorTitle}</w:t>
    </w:r>
</w:p>
</#if>

<w:p>
    <w:pPr><w:jc w:val="center"/></w:pPr>
    <w:r>
        <w:rPr><w:rFonts w:ascii="微软雅黑" w:eastAsia="微软雅黑"/>
               <w:sz w:val="28"/><w:szCs w:val="28"/>
               <w:color w:val="1A1A1A"/></w:rPr>
        <w:t xml:space="preserve">日期：${createdAt!""}</w:t>
    </w:r>
</w:p>

<#-- 封底留白后换页 -->
<w:p><w:pPr><w:spacing w:before="2400"/></w:pPr></w:p>
<w:p>
    <w:pPr><w:jc w:val="center"/></w:pPr>
    <w:r>
        <w:rPr><w:rFonts w:ascii="微软雅黑" w:eastAsia="微软雅黑"/>
               <w:sz w:val="22"/><w:szCs w:val="22"/>
               <w:color w:val="9CA3AF"/></w:rPr>
        <w:t xml:space="preserve">${companyName!"AI经验萃取平台"}</w:t>
    </w:r>
</w:p>

<#-- 分页 -->
<w:p><w:r><w:br w:type="page"/></w:r></w:p>

<#-- ============================
     正文：遍历六章
     ============================ -->
<#if chapters??>
<#list chapters as chapter>

<#-- 章节标题：24pt 深蓝 Bold，上方留白 40pt，下方留白 16pt，底部金色边框线 -->
<w:p>
    <w:pPr><w:spacing w:before="800" w:after="320"/>
           <w:pBdr><w:bottom w:val="single" w:sz="4" w:space="4" w:color="C8A45C"/></w:pBdr></w:pPr>
    <w:r>
        <w:rPr><w:rFonts w:ascii="微软雅黑" w:eastAsia="微软雅黑"/>
               <w:b/><w:bCs/>
               <w:sz w:val="48"/><w:szCs w:val="48"/>
               <w:color w:val="1A2B4C"/></w:rPr>
        <w:t xml:space="preserve"><#if chapter.title??>${chapter.title}</#if></w:t>
    </w:r>
</w:p>

<#-- ========== 案例故事（chapter.order == 1） ========== -->
<#if chapter.order == 1 && chapter.content??>
    <#list chapter.content?split("\n") as paragraph>
    <#if paragraph?trim != "">
    <w:p>
        <w:pPr><w:spacing w:line="360" w:lineRule="auto" w:after="240"/></w:pPr>
        <w:r>
            <w:rPr><w:rFonts w:ascii="微软雅黑" w:eastAsia="微软雅黑"/>
                   <w:sz w:val="22"/><w:szCs w:val="22"/>
                   <w:color w:val="1A1A1A"/></w:rPr>
            <w:t xml:space="preserve">    ${paragraph}</w:t>
        </w:r>
    </w:p>
    </#if>
    </#list>
</#if>

<#-- ========== 方法论步骤（chapter.order == 2） ========== -->
<#if chapter.order == 2 && chapter.steps??>
    <#list chapter.steps as step>
    <#-- 步骤名称 -->
    <w:p>
        <w:pPr><w:spacing w:before="400" w:after="120"/></w:pPr>
        <w:r>
            <w:rPr><w:rFonts w:ascii="微软雅黑" w:eastAsia="微软雅黑"/>
                   <w:b/><w:bCs/>
                   <w:sz w:val="28"/><w:szCs w:val="28"/>
                   <w:color w:val="1A2B4C"/></w:rPr>
            <w:t xml:space="preserve">第${step.order}步：${step.name!""}</w:t>
        </w:r>
    </w:p>

    <#-- 步骤核心动作 -->
    <w:p>
        <w:pPr><w:spacing w:line="360" w:lineRule="auto" w:after="120"/></w:pPr>
        <w:r>
            <w:rPr><w:rFonts w:ascii="微软雅黑" w:eastAsia="微软雅黑"/>
                   <w:sz w:val="22"/><w:szCs w:val="22"/>
                   <w:color w:val="1A1A1A"/></w:rPr>
            <w:t xml:space="preserve">核心动作：${step.action!""}</w:t>
        </w:r>
    </w:p>

    <#-- 关键话术（金色引用块） -->
    <#if step.script?? && step.script != "">
    <w:p>
        <w:pPr>
            <w:pBdr><w:left w:val="single" w:sz="18" w:space="8" w:color="C8A45C"/></w:pBdr>
            <w:shd w:val="clear" w:color="auto" w:fill="FAF7F2"/>
            <w:spacing w:before="120" w:after="120"/>
            <w:ind w:left="120" w:right="120"/>
        </w:pPr>
        <w:r>
            <w:rPr><w:rFonts w:ascii="微软雅黑" w:eastAsia="微软雅黑"/>
                   <w:i/><w:iCs/>
                   <w:sz w:val="22"/><w:szCs w:val="22"/>
                   <w:color w:val="1A2B4C"/></w:rPr>
            <w:t xml:space="preserve">"${step.script}"</w:t>
        </w:r>
    </w:p>
    </#if>

    <#-- 常见错误（红色引用块） -->
    <#if step.mistake?? && step.mistake != "">
    <w:p>
        <w:pPr>
            <w:pBdr><w:left w:val="single" w:sz="18" w:space="8" w:color="EF4444"/></w:pBdr>
            <w:shd w:val="clear" w:color="auto" w:fill="FEF2F2"/>
            <w:spacing w:before="120" w:after="120"/>
            <w:ind w:left="120" w:right="120"/>
        </w:pPr>
        <w:r>
            <w:rPr><w:rFonts w:ascii="微软雅黑" w:eastAsia="微软雅黑"/>
                   <w:sz w:val="22"/><w:szCs w:val="22"/>
                   <w:color w:val="B91C1C"/></w:rPr>
            <w:t xml:space="preserve">⚠ 常见错误：${step.mistake}</w:t>
        </w:r>
    </w:p>
    </#if>
    </#list>
</#if>

<#-- ========== 关键决策点（chapter.order == 3） ========== -->
<#if chapter.order == 3 && chapter.decisions??>
    <#list chapter.decisions as decision>
    <w:p>
        <w:pPr><w:spacing w:before="320" w:after="120"/></w:pPr>
        <w:r>
            <w:rPr><w:rFonts w:ascii="微软雅黑" w:eastAsia="微软雅黑"/>
                   <w:b/><w:bCs/>
                   <w:sz w:val="26"/><w:szCs w:val="26"/>
                   <w:color w:val="1A2B4C"/></w:rPr>
            <w:t xml:space="preserve">${decision.title!""}</w:t>
        </w:r>
    </w:p>
    <#if decision.options??>
        <#list decision.options as option>
        <w:p>
            <w:pPr><w:spacing w:line="360" w:lineRule="auto"/></w:pPr>
            <w:r>
                <w:rPr><w:rFonts w:ascii="微软雅黑" w:eastAsia="微软雅黑"/>
                       <w:sz w:val="22"/><w:szCs w:val="22"/>
                       <w:color w:val="1A1A1A"/></w:rPr>
                <w:t xml:space="preserve"><#if decision.chosen?? && option == decision.chosen>✅ ${option}<#else>   ${option}</#if></w:t>
            </w:r>
        </w:p>
        </#list>
    </#if>
    <#if decision.reason??>
    <w:p>
        <w:pPr><w:shd w:val="clear" w:color="auto" w:fill="F0F3F8"/>
               <w:spacing w:before="120" w:after="120"/>
               <w:ind w:left="120" w:right="120"/></w:pPr>
        <w:r>
            <w:rPr><w:rFonts w:ascii="微软雅黑" w:eastAsia="微软雅黑"/>
                   <w:sz w:val="22"/><w:szCs w:val="22"/>
                   <w:color w:val="2D4373"/></w:rPr>
            <w:t xml:space="preserve">💡 ${decision.reason}</w:t>
        </w:r>
    </w:p>
    </#if>
    </#list>
</#if>

<#-- ========== 专家心法（chapter.order == 4）含一句话介绍 + 金句 + 比喻 ========== -->
<#if chapter.order == 4>
    <#if chapter.oneliner?? && chapter.oneliner != "">
    <w:p>
        <w:pPr>
            <w:pBdr><w:left w:val="single" w:sz="18" w:space="8" w:color="C8A45C"/></w:pBdr>
            <w:shd w:val="clear" w:color="auto" w:fill="FAF7F2"/>
            <w:spacing w:before="160" w:after="160"/>
            <w:ind w:left="120" w:right="120"/>
        </w:pPr>
        <w:r>
            <w:rPr><w:rFonts w:ascii="微软雅黑" w:eastAsia="微软雅黑"/>
                   <w:b/><w:bCs/><w:i/><w:iCs/>
                   <w:sz w:val="28"/><w:szCs w:val="28"/>
                   <w:color w:val="1A2B4C"/></w:rPr>
            <w:t xml:space="preserve">"${oneliner}"</w:t>
        </w:r>
    </w:p>
    </#if>

    <#if chapter.metaphor?? && chapter.metaphor != "">
    <w:p>
        <w:pPr><w:jc w:val="right"/><w:spacing w:after="240"/></w:pPr>
        <w:r>
            <w:rPr><w:rFonts w:ascii="微软雅黑" w:eastAsia="微软雅黑"/>
                   <w:sz w:val="22"/><w:szCs w:val="22"/>
                   <w:color w:val="6B7280"/></w:rPr>
            <w:t xml:space="preserve">——像${metaphor}</w:t>
        </w:r>
    </w:p>
    </#if>

    <#if chapter.quotes??>
        <#list chapter.quotes as quote>
        <w:p>
            <w:pPr>
                <w:pBdr><w:left w:val="single" w:sz="18" w:space="8" w:color="C8A45C"/></w:pBdr>
                <w:shd w:val="clear" w:color="auto" w:fill="FAF7F2"/>
                <w:spacing w:before="120" w:after="120"/>
                <w:ind w:left="120" w:right="120"/>
            </w:pPr>
            <w:r>
                <w:rPr><w:rFonts w:ascii="微软雅黑" w:eastAsia="微软雅黑"/>
                       <w:i/><w:iCs/>
                       <w:sz w:val="22"/><w:szCs w:val="22"/>
                       <w:color w:val="1A2B4C"/></w:rPr>
                <w:t xml:space="preserve">"${quote}"</w:t>
            </w:r>
        </w:p>
        </#list>
    </#if>
</#if>

<#-- ========== 避坑指南（chapter.order == 5） ========== -->
<#if chapter.order == 5 && chapter.pitfalls??>
    <#list chapter.pitfalls as pitfall>
    <w:p>
        <w:pPr><w:spacing w:before="240" w:after="80"/></w:pPr>
        <w:r>
            <w:rPr><w:rFonts w:ascii="微软雅黑" w:eastAsia="微软雅黑"/>
                   <w:b/><w:bCs/>
                   <w:sz w:val="24"/><w:szCs w:val="24"/>
                   <w:color w:val="1A2B4C"/></w:rPr>
            <w:t xml:space="preserve">⚠ ${pitfall.title!""}</w:t>
        </w:r>
    </w:p>
    <#if pitfall.solution??>
    <w:p>
        <w:pPr><w:spacing w:line="360" w:lineRule="auto" w:after="160"/></w:pPr>
        <w:r>
            <w:rPr><w:rFonts w:ascii="微软雅黑" w:eastAsia="微软雅黑"/>
                   <w:sz w:val="22"/><w:szCs w:val="22"/>
                   <w:color w:val="059669"/></w:rPr>
            <w:t xml:space="preserve">   → ${pitfall.solution}</w:t>
        </w:r>
    </w:p>
    </#if>
    </#list>
</#if>

<#-- ========== 行动检查清单 + 情景练习（chapter.order == 6） ========== -->
<#if chapter.order == 6>

    <#-- 检查清单 -->
    <#if chapter.checklist??>
    <w:p>
        <w:pPr><w:spacing w:before="400" w:after="200"/></w:pPr>
        <w:r>
            <w:rPr><w:rFonts w:ascii="微软雅黑" w:eastAsia="微软雅黑"/>
                   <w:b/><w:bCs/>
                   <w:sz w:val="28"/><w:szCs w:val="28"/>
                   <w:color w:val="1A2B4C"/></w:rPr>
            <w:t xml:space="preserve">✅ 行动检查清单</w:t>
        </w:r>
    </w:p>

    <#-- 表头 -->
    <w:tbl>
        <w:tblPr>
            <w:tblW w:w="9000" w:type="dxa"/>
            <w:tblBorders>
                <w:top w:val="single" w:sz="4" w:space="0" w:color="1A2B4C"/>
                <w:left w:val="single" w:sz="4" w:space="0" w:color="1A2B4C"/>
                <w:bottom w:val="single" w:sz="4" w:space="0" w:color="1A2B4C"/>
                <w:right w:val="single" w:sz="4" w:space="0" w:color="1A2B4C"/>
                <w:insideH w:val="single" w:sz="2" w:space="0" w:color="D1D5DB"/>
                <w:insideV w:val="single" w:sz="2" w:space="0" w:color="D1D5DB"/>
            </w:tblBorders>
        </w:tblPr>

        <w:tblGrid>
            <w:gridCol w:w="800"/>
            <w:gridCol w:w="8200"/>
        </w:tblGrid>

        <#-- 表头行 -->
        <w:tr>
            <w:tblPrEx><w:tblW w:w="9000" w:type="dxa"/></w:tblPrEx>
            <w:tc>
                <w:tcPr>
                    <w:tcW w:w="800" w:type="dxa"/>
                    <w:shd w:val="clear" w:color="auto" w:fill="1A2B4C"/>
                </w:tcPr>
                <w:p>
                    <w:pPr><w:jc w:val="center"/></w:pPr>
                    <w:r>
                        <w:rPr><w:rFonts w:ascii="微软雅黑" w:eastAsia="微软雅黑"/>
                               <w:b/><w:bCs/>
                               <w:sz w:val="22"/><w:szCs w:val="22"/>
                               <w:color w:val="FFFFFF"/></w:rPr>
                        <w:t>步骤</w:t>
                    </w:r>
                </w:p>
            </w:tc>
            <w:tc>
                <w:tcPr>
                    <w:tcW w:w="8200" w:type="dxa"/>
                    <w:shd w:val="clear" w:color="auto" w:fill="1A2B4C"/>
                </w:tcPr>
                <w:p>
                    <w:pPr><w:jc w:val="center"/></w:pPr>
                    <w:r>
                        <w:rPr><w:rFonts w:ascii="微软雅黑" w:eastAsia="微软雅黑"/>
                               <w:b/><w:bCs/>
                               <w:sz w:val="22"/><w:szCs w:val="22"/>
                               <w:color w:val="FFFFFF"/></w:rPr>
                        <w:t>动作描述</w:t>
                    </w:r>
                </w:p>
            </w:tc>
        </w:tr>

        <#-- 内容行（交替背景） -->
        <#list chapter.checklist as item>
        <w:tr>
            <w:tblPrEx><w:tblW w:w="9000" w:type="dxa"/></w:tblPrEx>
            <w:tc>
                <w:tcPr>
                    <w:tcW w:w="800" w:type="dxa"/>
                    <w:shd w:val="clear" w:color="auto"
                           w:fill="<#if item_index % 2 == 0>FFFFFF<#else>F7F8FA</#if>"/>
                </w:tcPr>
                <w:p>
                    <w:pPr><w:jc w:val="center"/></w:pPr>
                    <w:r>
                        <w:rPr><w:rFonts w:ascii="微软雅黑" w:eastAsia="微软雅黑"/>
                               <w:sz w:val="22"/><w:szCs w:val="22"/>
                               <w:color w:val="1A2B4C"/></w:rPr>
                        <w:t>${item.step!""}</w:t>
                    </w:r>
                </w:p>
            </w:tc>
            <w:tc>
                <w:tcPr>
                    <w:tcW w:w="8200" w:type="dxa"/>
                    <w:shd w:val="clear" w:color="auto"
                           w:fill="<#if item_index % 2 == 0>FFFFFF<#else>F7F8FA</#if>"/>
                </w:tcPr>
                <w:p>
                    <w:pPr><w:spacing w:line="360" w:lineRule="auto"/></w:pPr>
                    <w:r>
                        <w:rPr><w:rFonts w:ascii="微软雅黑" w:eastAsia="微软雅黑"/>
                               <w:sz w:val="22"/><w:szCs w:val="22"/>
                               <w:color w:val="1A1A1A"/></w:rPr>
                        <w:t xml:space="preserve">□  ${item.action!""}</w:t>
                    </w:r>
                </w:p>
            </w:tc>
        </w:tr>
        </#list>
    </w:tbl>
    </#if>

    <#-- 情景练习 -->
    <#if chapter.practiceScene??>
    <w:p>
        <w:pPr><w:spacing w:before="600" w:after="160"/></w:pPr>
        <w:r>
            <w:rPr><w:rFonts w:ascii="微软雅黑" w:eastAsia="微软雅黑"/>
                   <w:b/><w:bCs/>
                   <w:sz w:val="28"/><w:szCs w:val="28"/>
                   <w:color w:val="1A2B4C"/></w:rPr>
            <w:t xml:space="preserve">🎯 情景练习</w:t>
        </w:r>
    </w:p>

    <#if chapter.practiceScene.title??>
    <w:p>
        <w:pPr><w:spacing w:line="360" w:lineRule="auto"/></w:pPr>
        <w:r>
            <w:rPr><w:rFonts w:ascii="微软雅黑" w:eastAsia="微软雅黑"/>
                   <w:b/><w:bCs/>
                   <w:sz w:val="24"/><w:szCs w:val="24"/>
                   <w:color w:val="1A2B4C"/></w:rPr>
            <w:t xml:space="preserve">${chapter.practiceScene.title}</w:t>
        </w:r>
    </w:p>
    </#if>

    <#if chapter.practiceScene.setting??>
    <w:p>
        <w:pPr>
            <w:pBdr><w:left w:val="single" w:sz="18" w:space="8" w:color="C8A45C"/></w:pBdr>
            <w:shd w:val="clear" w:color="auto" w:fill="FAF7F2"/>
            <w:spacing w:before="120" w:after="120"/>
            <w:ind w:left="120" w:right="120"/>
        </w:pPr>
        <w:r>
            <w:rPr><w:rFonts w:ascii="微软雅黑" w:eastAsia="微软雅黑"/>
                   <w:sz w:val="22"/><w:szCs w:val="22"/>
                   <w:color w:val="6B7280"/></w:rPr>
            <w:t xml:space="preserve">${chapter.practiceScene.setting}</w:t>
        </w:r>
    </w:p>
    </#if>

    <#if chapter.practiceScene.customerLine??>
    <w:p>
        <w:pPr>
            <w:pBdr><w:left w:val="single" w:sz="18" w:space="8" w:color="C8A45C"/></w:pBdr>
            <w:shd w:val="clear" w:color="auto" w:fill="FAF7F2"/>
            <w:spacing w:before="80" w:after="120"/>
            <w:ind w:left="120" w:right="120"/>
        </w:pPr>
        <w:r>
            <w:rPr><w:rFonts w:ascii="微软雅黑" w:eastAsia="微软雅黑"/>
                   <w:b/><w:bCs/>
                   <w:sz w:val="22"/><w:szCs w:val="22"/>
                   <w:color w:val="1A1A1A"/></w:rPr>
            <w:t xml:space="preserve">客户："${chapter.practiceScene.customerLine}"</w:t>
        </w:r>
    </w:p>
    </#if>
    </#if>
</#if>

<#-- 章末分页（最后一章不需分页） -->
<#if chapter_has_next>
<w:p><w:r><w:br w:type="page"/></w:r></w:p>
</#if>

</#list>
</#if>

</w:body>
</w:document>
