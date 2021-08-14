#!/bin/sh

# Licensed to the Apache Software Foundation (ASF) under one or more
# contributor license agreements.  See the NOTICE file distributed with
# this work for additional information regarding copyright ownership.
# The ASF licenses this file to You under the Apache License, Version 2.0
# (the "License"); you may not use this file except in compliance with
# the License.  You may obtain a copy of the License at
#
#     http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.

# -----------------------------------------------------------------------------
# Start Script for the CATALINA Server
# -----------------------------------------------------------------------------

# Better OS/400 detection: see Bugzilla 31132
os400=false
# 判断操作系统总类是否是os400 ,这个语法相当于case when 以OS400开头的uname  就将 os400设置为true
case "`uname`" in
OS400*) os400=true;;
esac

# resolve links - $0 may be a softlink
# 获取shell 文件本身的名称
PRG="$0"
# 判断当前文件名称是否是一个软连接
while [ -h "$PRG" ] ; do
  # 如果当前名称是一个软连接,那就 将当前 软连接的全称打印出来
  ls=`ls -ld "$PRG"`
  # 获取到当前软连接的全称 ，通过正则表达式匹配到真实路径，expr xx : xx 的方式 是匹配模式  .*代表任何字符重复0次或多次，返回值为括号中内容
  link=`expr "$ls" : '.*-> \(.*\)$'`
  if expr "$link" : '/.*' > /dev/null; then
    PRG="$link"
  else
    PRG=`dirname "$PRG"`/"$link"
  fi
done
# 得到源文件的真实路径
PRGDIR=`dirname "$PRG"`
# 声明EXECUTABLE 变量赋值初始值catalina.sh
EXECUTABLE=catalina.sh

# Check that target executable exists
if $os400; then
  # -x will Only work on the os400 if the files are:
  # 1. owned by the user
  # 2. owned by the PRIMARY group of the user
  # this will not work if the user belongs in secondary groups
  #其中command－line是在终端上键入的一条普通命令行。然而当在它前面放上eval时，其结果是shell在执行命令行之前扫描它两次。如：
  #pipe="|"
  #
  #eval ls $pipe wc -l
  #shell第1次扫描命令行时，它替换出pipe的值｜，接着eval使它再次扫描命令行，这时shell把｜作为管道符号了。
  eval
else
  # 如果此真实文件不是一个可执行文件 ，那么输出一堆日志后退出 shell脚本
  if [ ! -x "$PRGDIR"/"$EXECUTABLE" ]; then
    echo "Cannot find $PRGDIR/$EXECUTABLE"
    echo "The file is absent or does not have execute permission"
    echo "This file is needed to run this program"
    exit 1
  fi
fi
#执行真实的路径 下的catalina.sh 脚本  ,这一步其实就是跳转到  catalina.sh 脚本中去
exec "$PRGDIR"/"$EXECUTABLE" start "$@"
