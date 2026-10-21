#!/bin/bash
# 清理 intranet_app_manager 里的旧安装包：删数据库记录（tb_package 及其关联的 tb_storage、tb_provision）和 storage 里的文件。
# 由 Jenkins 工程 AppManager_Cleanup 调用；部署位置 /Users/jenkins/Services/intranet_app_manager/bin/cleanup-packages.sh
#
# 用法：cleanup-packages.sh <保留天数> [仅预览 true|false，默认 true]
#
# - 各 App 的当前版本（tb_app.currentid）不管多旧都保留，否则 App 页打不开
# - 先在一个事务里删库，提交成功才删文件；删库失败整批回滚，文件一个不动
# - 数据库账号读 config/cleanup.my.cnf（权限 600，内容同 app.jar 里的 spring.datasource.*）
set -euo pipefail

KEEP_DAYS=${1:?缺少保留天数}
DRY_RUN=${2:-true}
case "$KEEP_DAYS" in
    '' | *[!0-9]*)
        echo "保留天数必须是正整数：$KEEP_DAYS" >&2
        exit 1
        ;;
esac
if [ "$KEEP_DAYS" -lt 1 ]; then
    echo "保留天数至少为 1" >&2
    exit 1
fi

BASE=/Users/jenkins/Services/intranet_app_manager
STORAGE="$BASE/storage"
MYSQL=/opt/homebrew/opt/mysql@8.4/bin/mysql
db() {
    "$MYSQL" --defaults-extra-file="$BASE/config/cleanup.my.cnf" --batch --skip-column-names "$@"
}

cutoff_s=$(($(date +%s) - KEEP_DAYS * 86400))
echo "保留最近 $KEEP_DAYS 天：删除 $(date -r "$cutoff_s" '+%Y-%m-%d %H:%M:%S') 之前上传的安装包（各 App 的当前版本除外）"
echo "删除前 storage 占用 $(du -sh "$STORAGE" | cut -f1)，磁盘剩余 $(df -h "$STORAGE" | awk 'NR==2 {print $4}')"
echo

LIST=$(mktemp)
trap 'rm -f "$LIST"' EXIT
# 空值换成 -：read 按 tab 拆分时，连续的 tab 会被并成一个，空字段会让后面的列错位
db -e "
SELECT p.id, p.source_file_id, p.icon_file_id, IFNULL(p.provision_id, '-'), s.key_, i.key_, s.size,
       p.platform, p.version, p.build_version,
       DATE_FORMAT(FROM_UNIXTIME(p.create_time / 1000), '%Y-%m-%d %H:%i'), IFNULL(NULLIF(p.extra, ''), '-')
FROM tb_package p
JOIN tb_storage s ON s.id = p.source_file_id
JOIN tb_storage i ON i.id = p.icon_file_id
WHERE p.create_time < $((cutoff_s * 1000))
  AND NOT EXISTS (SELECT 1 FROM tb_app a WHERE a.currentid = p.id)
ORDER BY p.create_time" >"$LIST"

count=$(wc -l <"$LIST" | tr -d ' ')
if [ "$count" -eq 0 ]; then
    echo "没有需要删除的安装包"
    exit 0
fi

pkg_ids=''
storage_ids=''
provision_ids=''
total=0
printf '%-16s  %-7s  %-8s  %-6s  %7s  %s\n' '上传时间' '平台' '版本' '构建号' '大小MB' '来源'
while IFS=$'\t' read -r id sid iid vid skey ikey size platform ver build created extra; do
    # id 都是 Hibernate 生成的 32 位十六进制，拼进 SQL 前校验一遍
    for v in "$id" "$sid" "$iid"; do
        if ! [[ "$v" =~ ^[0-9a-f]{32}$ ]]; then
            echo "异常的记录 id：$v，中止" >&2
            exit 1
        fi
    done
    pkg_ids+="${pkg_ids:+,}'$id'"
    storage_ids+="${storage_ids:+,}'$sid','$iid'"
    if [ "$vid" != '-' ]; then
        if ! [[ "$vid" =~ ^[0-9a-f]{32}$ ]]; then
            echo "异常的描述文件 id：$vid，中止" >&2
            exit 1
        fi
        provision_ids+="${provision_ids:+,}'$vid'"
    fi
    total=$((total + size))
    printf '%-16s  %-7s  %-8s  %-6s  %7d  %s\n' "$created" "$platform" "$ver" "$build" $((size / 1024 / 1024)) "$extra"
done <"$LIST"
echo
echo "共 $count 个安装包，约 $(awk -v b="$total" 'BEGIN {printf "%.1f", b / 1024 / 1024 / 1024}') GB"

if [ "$DRY_RUN" = true ]; then
    echo
    echo "仅预览，没有删除任何东西。取消勾选「仅预览」再运行一次才会真正删除。"
    exit 0
fi

# 先删引用方 tb_package，再删被引用的 tb_storage、tb_provision；任何一条失败（比如期间某个包成了当前版本，外键拦住）
# mysql 立即退出，连接断开时未提交的事务自动回滚
sql="START TRANSACTION;
DELETE FROM tb_package WHERE id IN ($pkg_ids);
DELETE FROM tb_storage WHERE id IN ($storage_ids);"
if [ -n "$provision_ids" ]; then
    sql+="
DELETE FROM tb_provision WHERE id IN ($provision_ids);"
fi
sql+="
COMMIT;"
db -e "$sql"
echo "数据库记录已删除"

removed=0
while IFS=$'\t' read -r _ _ _ _ skey ikey _; do
    for key in "$skey" "$ikey"; do
        # 文件名由 StorageUtil.generateKey 生成：20 位字母数字 + 扩展名，不合规的一律不碰
        if ! [[ "$key" =~ ^[A-Za-z0-9]{20}\.(ipa|apk|png)$ ]]; then
            echo "跳过异常文件名：$key" >&2
            continue
        fi
        # PNG 去重用的是硬链接，删掉一个链接不影响其他记录
        if rm -f "$STORAGE/$key"; then
            removed=$((removed + 1))
        fi
    done
done <"$LIST"
echo "已删除 $removed 个文件"
echo
echo "删除后 storage 占用 $(du -sh "$STORAGE" | cut -f1)，磁盘剩余 $(df -h "$STORAGE" | awk 'NR==2 {print $4}')"
