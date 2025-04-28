<%@page import="net.i2p.crypto.SHA256Generator,net.i2p.data.Base32,net.i2p.data.Base64,net.i2p.data.DataHelper,net.i2p.zzzot.*" %><%

/*
 *  Copyright 2010 zzz (zzz@mail.i2p)
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 *
 */

		response.setStatus(406);
		out.println("SC_NOT_ACCEPTABLE");

%>